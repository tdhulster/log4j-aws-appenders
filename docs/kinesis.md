# Kinesis Streams

The Kinesis Streams appender is intended to be an entry point for log analytics, either
as a direct feed to an analytics application, or via Kinesis Firehose to ElasticSearch
or other destinations (note that this can also be an easy way to back-up logs to S3).

The Kinesis implementation provides (will provide) the following features:

* [x] Configurable destination stream, with substitution variables to specify stream name
* [x] Auto-creation of streams, with configurable number of shards
* [x] JSON messages (via layout)
* [ ] Random partition keys, to support high-volume streams


## Configuration

Your Log4J configuration will look something like this:

    log4j.rootLogger=INFO, kinesis

    log4j.appender.kinesis=com.kdgregory.log4j.aws.KinesisAppender
    log4j.appender.kinesis.layout=org.apache.log4j.PatternLayout
    log4j.appender.kinesis.layout.ConversionPattern=%d [%t] %-5p %c %x - %m%n

    log4j.appender.kinesis.streamName={env:APP_NAME}
    log4j.appender.kinesis.partitionKey={pid}
    log4j.appender.kinesis.shardCount=2
    log4j.appender.kinesis.batchDelay=500


The appender provides the following properties (also described in the JavaDoc):

Name                | Description
--------------------|----------------------------------------------------------------
`streamName`        | The name of the log stream that will receive messages. This stream will be created if it doesn't already exist.
`partitionKey`      | A string used to assign messages to shards; see below for more information.
`shardCount`        | When creating a stream, specifies the number of shards to use. Defaults to 1.
`retentionPeriod`   | When creating a stream, specifies the retention period for messages in hours. Per AWS, the minimum is 24 (the default) and the maximum is 168 (7 days).
`batchDelay`        | The time, in milliseconds, that the writer will wait to accumulate messages for a batch. See [design doc](design.md#message-batches) for more information.
`discardThreshold`  | The threshold count for discarding messages; default is 10,000. See [design doc](design.md#message-discard) for more information.
`discardAction`     | Which messages will be discarded once the threshold is passed: `oldest` (the default), `newest`, or `none`.

The `streamName` and `partitionKey` properties may use [substutions](substitutions.md).


## Permissions

To use this appender you will need to grant the following IAM permissions:

* `kinesis:CreateStream`
* `kinesis:DescribeStream`
* `kinesis:IncreaseStreamRetentionPeriod`
* `kinesis:PutRecords`


## Partition Keys

Kinesis supports high-performance parallel writes by supporting multiple shards per stream; each
shard can accept up to 1 MB/second of data. However, Kinesis does not automatically distribute
data among available shards; instead, it requires each record to have a partition key, and hashes
that partition key to determine which shard is used to store the record.

The Kinesis appender currently requires an explicit partition key; by default this key is the
application startup timestamp. Since the appender only has one partition key, it makes no sense
to use multiple shards if you only have one application logging to a stream, as the partition
key will always hash to the same shard. Where differing partition keys are useful is when you
have multiple applications writing to the same stream (typically, a horizontally-scaled group,
in which each application writes its own messages but there will be multiple writers).

> As a future enhancement, the Kinesis appender will allow you to configure a per-message
  random partition key. This will allow increased logging throughput for high-volume
  logging from a single application.

Unfortunately, partition keys are not retained in the stream, so cannot be used as a way to
differentiate messages from different loggers.
