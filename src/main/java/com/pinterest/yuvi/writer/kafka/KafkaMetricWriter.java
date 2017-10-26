package com.pinterest.yuvi.writer.kafka;

import com.pinterest.yuvi.chunk.ChunkManager;
import com.pinterest.yuvi.chunk.ReadOnlyChunkInsertionException;
import com.pinterest.yuvi.thrift.TextMessage;
import com.pinterest.yuvi.writer.MetricWriter;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.RejectedExecutionException;

/**
 * KafkaMetricWriter ingests metrics wrapped in a TextMessage from Kafka into a Yuvi ChunkManager.
 *
 * Currently, this class ingests metrics in a thrift format since it is more efficient than the JSON
 * format used by OpenTSDB kafka plugin. The class is implemented in a way that the ingestion format
 * from kafka is pluggable, so this can be changed easily in future.
 *
 * If both a kafkaTopicName and kafkaTopicPartition are specified, we will assign the consumer to
 * that partition. If kafkaTopicPartition is empty, we will subscribe to the entire topic.
 *
 * TODO: Support ingesting messages in OpenTSDB JSON format from Kafka.
 */
public class KafkaMetricWriter implements MetricWriter {

  private static Logger LOG = LoggerFactory.getLogger(KafkaMetricWriter.class);

  // Log delayed metrics to a different logger to reduce log spam.
  private static Logger DELAYED_METRICS_LOG = LoggerFactory.getLogger("DELAYED_METRICS");

  private final ChunkManager chunkManager;

  private final KafkaConsumer consumer;
  private final String kafkaTopic;
  private final int kafkaTopicPartition;
  private long kafkaPollTimeoutMs = 100;

  public KafkaMetricWriter(ChunkManager chunkManager, String kafkaTopicName,
                           String kafkaTopicPartition, String kafkaBootStrapServers,
                           String kafkaClientGroup, String kafkaAutoCommit,
                           String kafkaAutoCommitInterval, String kafkaSessionTimeout) {

    LOG.info("Kafka params are: kafkaTopicName: {}, kafkaTopicPartition: {}, "
            + "kafkaBootstrapServers:{}, kafkaClientGroup: {}, kafkaAutoCommit:{}, "
            + "kafkaAutoCommitInterval: {}, kafkaSessionTimeout: {}", kafkaTopicName,
        kafkaTopicPartition, kafkaBootStrapServers, kafkaClientGroup, kafkaAutoCommit,
        kafkaAutoCommitInterval, kafkaSessionTimeout);

    if (chunkManager == null || kafkaTopicPartition == null
        || kafkaTopicName == null || kafkaTopicName.isEmpty()
        || kafkaBootStrapServers == null || kafkaBootStrapServers.isEmpty()
        || kafkaClientGroup == null || kafkaClientGroup.isEmpty()
        || kafkaAutoCommit == null || kafkaAutoCommit.isEmpty()
        || kafkaAutoCommitInterval == null || kafkaAutoCommitInterval.isEmpty()
        || kafkaSessionTimeout == null || kafkaSessionTimeout.isEmpty()) {
      throw new IllegalArgumentException("Kafka params can't be null or empty.");
    }

    this.chunkManager = chunkManager;

    // Create kafka consumer
    this.kafkaTopic = kafkaTopicName;

    int sessionTimeoutMs = new Integer(kafkaSessionTimeout.trim());

    Properties props = new Properties();
    props.put("bootstrap.servers", kafkaBootStrapServers);
    props.put("group.id", kafkaClientGroup);
    props.put("enable.auto.commit", kafkaAutoCommit);
    props.put("auto.commit.interval.ms", kafkaAutoCommitInterval);
    props.put("session.timeout.ms", sessionTimeoutMs);
    props.put("heartbeat.interval.ms", sessionTimeoutMs/3);
    props.put("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
    props.put("value.deserializer", "com.pinterest.yuvi.writer.kafka.ThriftTextMessageDeserializer");
    this.consumer = new KafkaConsumer<>(props);
    if (kafkaTopicPartition.isEmpty()) {
      this.kafkaTopicPartition = -1;
      LOG.info("Subscribing to kafka topic {}", this.kafkaTopic);
      consumer.subscribe(Arrays.asList(this.kafkaTopic),
          new MetricsConsumerRebalanceListener());
    } else {
      this.kafkaTopicPartition = new Integer(kafkaTopicPartition);
      LOG.info("Assigned to kafka topic {} and partition {}",
          this.kafkaTopic, this.kafkaTopicPartition);
      consumer.assign(Arrays.asList(new TopicPartition(this.kafkaTopic, this.kafkaTopicPartition)));
    }
  }

  public void start() {
    LOG.info("Starting metrics reader.");

    while (true) {
      try {
        ConsumerRecords<byte[], TextMessage> records;
        records = consumer.poll(kafkaPollTimeoutMs);
        LOG.debug("Fetched records." + records.count());

        try {
          insertRecords(records);
        } catch (Exception e) {
          LOG.error("Error processing messages from Kafka", e);
        }
      } catch (RejectedExecutionException e) {
        // This case shouldn't happen since there is only one thread queuing tasks here and we check
        // that the queue is empty before polling kafka.
        LOG.error("Rejected execution shouldn't happen ", e);
      } catch (Exception e) {
        LOG.error("Unhandled exception ", e);
      }
    }
  }

  private void insertRecords(ConsumerRecords<byte[], TextMessage> records) {
    int validMetrics = 0;
    int invalidMetrics = 0;
    int delayedMetrics = 0;
    int recordCount = 0;
    for (ConsumerRecord<byte[], TextMessage> record : records) {
      recordCount = recordCount + 1;
      List<String> metrics = record.value().getMessages();
      for (String metric: metrics) {
        try {
          chunkManager.addMetric(metric);
          validMetrics = validMetrics + 1;
        } catch (ReadOnlyChunkInsertionException e) {
          DELAYED_METRICS_LOG.debug("Error ingesting metric {}", metric);
          delayedMetrics = delayedMetrics + 1;
        } catch (Exception e) {
          LOG.error("Error ingesting metric {}", metric, e);
          invalidMetrics = invalidMetrics + 1;
        }
      }
    }
    LOG.info("Processed {} records with {} valid metrics, {} invalid metrics, {} delayed metrics",
        recordCount, validMetrics, invalidMetrics, delayedMetrics);
  }

  public void close() {
    // TODO: Close consumer properly.
    if (consumer != null) {
      LOG.info("Closing kafka consumer");
      consumer.close();
    }
  }
}
