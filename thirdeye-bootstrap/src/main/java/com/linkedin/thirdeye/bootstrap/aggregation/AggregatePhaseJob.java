package com.linkedin.thirdeye.bootstrap.aggregation;

import static com.linkedin.thirdeye.bootstrap.aggregation.AggregationJobConstants.AGG_CONFIG_PATH;
import static com.linkedin.thirdeye.bootstrap.aggregation.AggregationJobConstants.AGG_INPUT_AVRO_SCHEMA;
import static com.linkedin.thirdeye.bootstrap.aggregation.AggregationJobConstants.AGG_INPUT_PATH;
import static com.linkedin.thirdeye.bootstrap.aggregation.AggregationJobConstants.AGG_OUTPUT_PATH;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.mapred.AvroKey;
import org.apache.avro.mapreduce.AvroJob;
import org.apache.avro.mapreduce.AvroKeyInputFormat;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ByteWritable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;

/**
 * 
 * @author kgopalak <br/>
 * 
 *         INPUT: RAW DATA FILES. <br/>
 *         EACH RECORD OF THE FORMAT {DIMENSION, TIME, RECORD} <br/>
 *         MAP OUTPUT: {DIMENSION KEY, TIME, METRIC} <br/>
 *         REDUCE OUTPUT: DIMENSION KEY: SET{TIME_BUCKET, METRIC}
 */
public class AggregatePhaseJob extends Configured {
  private static final Logger LOG = LoggerFactory
      .getLogger(AggregatePhaseJob.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private String name;
  private Properties props;

  enum Constants {

  }

  public AggregatePhaseJob(String name, Properties props) {
    super(new Configuration());
    this.name = name;
    this.props = props;
  }

  public static class AggregationMapper
      extends
      Mapper<AvroKey<GenericRecord>, NullWritable, BytesWritable, BytesWritable> {
    private AggregationJobConfig config;
    private TimeUnit sourceTimeUnit;
    private TimeUnit aggregationTimeUnit;
    private List<String> dimensionNames;
    private List<String> metricNames;
    private List<MetricType> metricTypes;
    private MessageDigest md5;
    private MetricSchema metricSchema;
    private String[] dimensionValues;

    @Override
    public void setup(Context context) throws IOException, InterruptedException {
      LOG.info("AggregatePhaseJob.AggregationMapper.setup()");
      Configuration configuration = context.getConfiguration();
      FileSystem fileSystem = FileSystem.get(configuration);
      Path configPath = new Path(configuration.get(AGG_CONFIG_PATH.toString()));
      try {
        config = OBJECT_MAPPER.readValue(fileSystem.open(configPath),
            AggregationJobConfig.class);
        dimensionNames = config.getDimensionNames();
        metricNames = config.getMetricNames();
        metricTypes = Lists.newArrayList();
        for (String type : config.getMetricTypes()) {
          metricTypes.add(MetricType.valueOf(type));
        }
        metricSchema = new MetricSchema(config.getMetricNames(), metricTypes);
        sourceTimeUnit = TimeUnit.valueOf(config.getTimeUnit());
        aggregationTimeUnit = TimeUnit.valueOf(config
            .getAggregationGranularity());
        md5 = MessageDigest.getInstance("MD5");
        dimensionValues = new String[dimensionNames.size()];
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    @Override
    public void map(AvroKey<GenericRecord> record, NullWritable value,
        Context context) throws IOException, InterruptedException {

      for (int i = 0; i < dimensionNames.size(); i++) {
        String dimensionName = dimensionNames.get(i);
        String dimensionValue = "";
        Object val = record.datum().get(dimensionName);
        if (val != null) {
          dimensionValue = val.toString();
        }
        dimensionValues[i] = dimensionValue;
      }
      if (Math.random() > -1) {

        AggregationKey key = new AggregationKey(dimensionValues);
        String sourceTimeWindow = record.datum()
            .get(config.getTimeColumnName()).toString();

        long aggregationTimeWindow = aggregationTimeUnit.convert(
            Long.parseLong(sourceTimeWindow), sourceTimeUnit);
        AggregationTimeSeries series = new AggregationTimeSeries(metricSchema);
        for (int i = 0; i < metricNames.size(); i++) {
          String metricName = metricNames.get(i);
          Object object = record.datum().get(metricName);
          String metricValueStr = "0";
          if (object != null) {
            metricValueStr = object.toString();
          }
          Number metricValue = metricTypes.get(i).toNumber(metricValueStr);
          series.set(aggregationTimeWindow, metricName, (Integer) metricValue);
        }
        // byte[] digest = md5.digest(dimensionValues.toString().getBytes());

        byte[] serializedKey = key.toBytes();

        byte[] serializedMetrics = series.toBytes();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        baos.write(serializedKey.length);
        baos.write(serializedKey);
        baos.write(serializedMetrics.length);
        baos.write(serializedMetrics);

        context.write(new BytesWritable(serializedKey), new BytesWritable(
            serializedMetrics));
      }
    }

    @Override
    public void cleanup(Context context) throws IOException,
        InterruptedException {

    }

  }

  public static class AggregationReducer extends
      Reducer<BytesWritable, BytesWritable, BytesWritable, BytesWritable> {
    private AggregationJobConfig config;
    private TimeUnit sourceTimeUnit;
    private TimeUnit aggregationTimeUnit;
    private List<String> dimensionNames;
    private List<String> metricNames;
    private List<MetricType> metricTypes;
    private MetricSchema metricSchema;

    @Override
    public void setup(Context context) throws IOException, InterruptedException {
      Configuration configuration = context.getConfiguration();
      FileSystem fileSystem = FileSystem.get(configuration);
      Path configPath = new Path(configuration.get(AGG_CONFIG_PATH.toString()));
      try {
        config = OBJECT_MAPPER.readValue(fileSystem.open(configPath),
            AggregationJobConfig.class);
        dimensionNames = config.getDimensionNames();
        metricNames = config.getMetricNames();
        metricTypes = Lists.newArrayList();
        sourceTimeUnit = TimeUnit.valueOf(config.getTimeUnit());
        aggregationTimeUnit = TimeUnit.valueOf(config
            .getAggregationGranularity());
        for (String type : config.getMetricTypes()) {
          metricTypes.add(MetricType.valueOf(type));
        }
        metricSchema = new MetricSchema(config.getMetricNames(), metricTypes);
      } catch (Exception e) {
        throw new IOException(e);
      }
    }

    @Override
    public void reduce(BytesWritable aggregationKey,
        Iterable<BytesWritable> timeSeriesIterable, Context context)
        throws IOException, InterruptedException {
      AggregationTimeSeries out = new AggregationTimeSeries(metricSchema);
      // AggregationKey key =
      // AggregationKey.fromBytes(aggregationKey.getBytes());
      for (BytesWritable writable : timeSeriesIterable) {
        AggregationTimeSeries series = AggregationTimeSeries.fromBytes(
            writable.getBytes(), metricSchema);
        out.aggregate(series);
      }
      byte[] serializedBytes = out.toBytes();
      context.write(aggregationKey, new BytesWritable(serializedBytes));
    }
  }

  public void run() throws Exception {
    Job job = Job.getInstance(getConf());
    job.setJobName(name);
    job.setJarByClass(AggregatePhaseJob.class);

    // Avro schema
    Schema schema = new Schema.Parser().parse(FileSystem.get(getConf()).open(
        new Path(getAndCheck(AggregationJobConstants.AGG_INPUT_AVRO_SCHEMA
            .toString()))));
    LOG.info("{}", schema);

    // Map config
    job.setMapperClass(AggregationMapper.class);
    AvroJob.setInputKeySchema(job, schema);
    job.setInputFormatClass(AvroKeyInputFormat.class);
    job.setMapOutputKeyClass(BytesWritable.class);
    job.setMapOutputValueClass(BytesWritable.class);
    // AvroJob.setMapOutputKeySchema(job,
    // Schema.create(Schema.Type.STRING));
    // AvroJob.setMapOutputValueSchema(job, schema);

    // Reduce config
    job.setReducerClass(AggregationReducer.class);
    job.setOutputKeyClass(BytesWritable.class);
    job.setOutputValueClass(BytesWritable.class);

    // aggregation phase config
    Configuration configuration = job.getConfiguration();
    String inputPathDir = getAndSetConfiguration(configuration, AGG_INPUT_PATH);
    getAndSetConfiguration(configuration, AGG_CONFIG_PATH);
    getAndSetConfiguration(configuration, AGG_OUTPUT_PATH);
    getAndSetConfiguration(configuration, AGG_INPUT_AVRO_SCHEMA);
    LOG.info("Input path dir: " + inputPathDir);
    for (String inputPath : inputPathDir.split(",")) {
      LOG.info("Adding input:" + inputPath);
      Path input = new Path(inputPath);
      FileInputFormat.addInputPath(job, input);
    }

    FileOutputFormat.setOutputPath(job,
        new Path(getAndCheck(AGG_OUTPUT_PATH.toString())));

    job.waitForCompletion(true);
  }

  private String getAndSetConfiguration(Configuration configuration,
      AggregationJobConstants constant) {
    String value = getAndCheck(constant.toString());
    configuration.set(constant.toString(), value);
    return value;
  }

  private String getAndCheck(String propName) {
    String propValue = props.getProperty(propName);
    if (propValue == null) {
      throw new IllegalArgumentException(propName + " required property");
    }
    return propValue;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("usage: config.properties");
    }

    Properties props = new Properties();
    props.load(new FileInputStream(args[0]));

    AggregatePhaseJob job = new AggregatePhaseJob("aggregate_avro_job", props);
    job.run();
  }

}
