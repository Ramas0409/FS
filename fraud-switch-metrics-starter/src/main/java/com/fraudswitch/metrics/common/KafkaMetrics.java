package com.fraudswitch.metrics.common;

import com.fraudswitch.metrics.cardinality.CardinalityEnforcer;
import com.fraudswitch.metrics.config.MetricsConfigurationProperties;
import com.fraudswitch.metrics.core.MetricLabels;
import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * Common Kafka metrics for message publishing and consumption.
 * 
 * <p>Tracks:
 * - Message publish/consume counts
 * - Message processing duration
 * - Consumer lag
 * - Errors
 * 
 * @version 1.0.0
 * @since 2025-10-14
 */
@Slf4j
public class KafkaMetrics {

    private final MeterRegistry meterRegistry;
    private final CardinalityEnforcer cardinalityEnforcer;
    private final MetricsConfigurationProperties config;
    private final String metricNamePrefix;

    public KafkaMetrics(
            MeterRegistry meterRegistry,
            CardinalityEnforcer cardinalityEnforcer,
            MetricsConfigurationProperties config,
            String metricNamePrefix) {
        
        this.meterRegistry = meterRegistry;
        this.cardinalityEnforcer = cardinalityEnforcer;
        this.config = config;
        this.metricNamePrefix = metricNamePrefix;
        
        log.debug("KafkaMetrics initialized for prefix: {}", metricNamePrefix);
    }

    /**
     * Record a message published to Kafka.
     * 
     * @param topic Kafka topic name
     * @param partition Partition number
     * @param durationMs Time taken to publish
     */
    public void recordPublish(String topic, int partition, long durationMs) {
        recordPublish(topic, partition, durationMs, true, null);
    }

    /**
     * Record a message published to Kafka with error details.
     * 
     * @param topic Kafka topic name
     * @param partition Partition number
     * @param durationMs Time taken to publish
     * @param success Whether publish was successful
     * @param errorType Type of error if failed
     */
    public void recordPublish(String topic, int partition, long durationMs, boolean success, String errorType) {
        String metricName = metricNamePrefix + ".kafka_publish_total";
        String[] labels = {
                MetricLabels.Kafka.TOPIC, topic,
                MetricLabels.Kafka.PARTITION, String.valueOf(partition),
                MetricLabels.Kafka.OPERATION, MetricLabels.Kafka.OP_PUBLISH,
                MetricLabels.Common.STATUS, success ? "success" : "error"
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            log.debug("Dropped Kafka publish metric due to cardinality limits");
            return;
        }

        // Record publish count
        Tags tags = createTags(labels);
        Counter.builder(metricName)
                .description("Total number of messages published to Kafka")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Record publish duration
        Timer.builder(metricNamePrefix + ".kafka_publish_duration_ms")
                .description("Time taken to publish message to Kafka")
                .tags(tags)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        // Record error if failed
        if (!success && errorType != null) {
            Counter.builder(metricNamePrefix + ".kafka_publish_errors_total")
                    .description("Total number of Kafka publish errors")
                    .tags(tags.and(MetricLabels.Common.ERROR_TYPE, errorType))
                    .register(meterRegistry)
                    .increment();
        }

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record a message consumed from Kafka.
     * 
     * @param topic Kafka topic name
     * @param partition Partition number
     * @param consumerGroup Consumer group name
     * @param durationMs Time taken to process
     */
    public void recordConsume(String topic, int partition, String consumerGroup, long durationMs) {
        recordConsume(topic, partition, consumerGroup, durationMs, true, null);
    }

    /**
     * Record a message consumed from Kafka with error details.
     * 
     * @param topic Kafka topic name
     * @param partition Partition number
     * @param consumerGroup Consumer group name
     * @param durationMs Time taken to process
     * @param success Whether consumption was successful
     * @param errorType Type of error if failed
     */
    public void recordConsume(String topic, int partition, String consumerGroup, long durationMs, 
                              boolean success, String errorType) {
        String metricName = metricNamePrefix + ".kafka_consumed_total";
        String[] labels = {
                MetricLabels.Kafka.TOPIC, topic,
                MetricLabels.Kafka.PARTITION, String.valueOf(partition),
                MetricLabels.Kafka.CONSUMER_GROUP, consumerGroup,
                MetricLabels.Kafka.OPERATION, MetricLabels.Kafka.OP_CONSUME,
                MetricLabels.Common.STATUS, success ? "success" : "error"
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            log.debug("Dropped Kafka consume metric due to cardinality limits");
            return;
        }

        // Record consume count
        Tags tags = createTags(labels);
        Counter.builder(metricName)
                .description("Total number of messages consumed from Kafka")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Record processing duration
        Timer.builder(metricNamePrefix + ".kafka_consumption_duration_ms")
                .description("Time taken to process consumed message")
                .tags(tags)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        // Record error if failed
        if (!success && errorType != null) {
            Counter.builder(metricNamePrefix + ".kafka_consumption_errors_total")
                    .description("Total number of Kafka consumption errors")
                    .tags(tags.and(MetricLabels.Common.ERROR_TYPE, errorType))
                    .register(meterRegistry)
                    .increment();
        }

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record consumer lag for a topic partition.
     * 
     * @param topic Kafka topic name
     * @param partition Partition number
     * @param consumerGroup Consumer group name
     * @param lag Current lag (messages behind)
     */
    public void recordConsumerLag(String topic, int partition, String consumerGroup, long lag) {
        String metricName = metricNamePrefix + ".kafka_consumption_lag";
        String[] labels = {
                MetricLabels.Kafka.TOPIC, topic,
                MetricLabels.Kafka.PARTITION, String.valueOf(partition),
                MetricLabels.Kafka.CONSUMER_GROUP, consumerGroup
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            log.debug("Dropped Kafka lag metric due to cardinality limits");
            return;
        }

        Tags tags = createTags(labels);
        Gauge.builder(metricName, () -> lag)
                .description("Consumer lag in messages")
                .tags(tags)
                .register(meterRegistry);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record batch processing metrics.
     * 
     * @param topic Kafka topic name
     * @param batchSize Number of messages in batch
     * @param durationMs Time taken to process batch
     */
    public void recordBatchProcessing(String topic, int batchSize, long durationMs) {
        String metricName = metricNamePrefix + ".kafka_batch_processed_total";
        String[] labels = {
                MetricLabels.Kafka.TOPIC, topic
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);
        
        // Record batch count
        Counter.builder(metricName)
                .description("Total number of batches processed")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        // Record batch size
        DistributionSummary.builder(metricNamePrefix + ".kafka_batch_size")
                .description("Size of message batches")
                .tags(tags)
                .register(meterRegistry)
                .record(batchSize);

        // Record batch processing duration
        Timer.builder(metricNamePrefix + ".kafka_batch_duration_ms")
                .description("Time taken to process message batch")
                .tags(tags)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    /**
     * Record message size.
     * 
     * @param topic Kafka topic name
     * @param sizeBytes Message size in bytes
     * @param operation publish or consume
     */
    public void recordMessageSize(String topic, long sizeBytes, String operation) {
        String metricName = metricNamePrefix + ".kafka_message_size_bytes";
        String[] labels = {
                MetricLabels.Kafka.TOPIC, topic,
                MetricLabels.Kafka.OPERATION, operation
        };

        if (!cardinalityEnforcer.canRecordMetric(metricName, labels)) {
            return;
        }

        Tags tags = createTags(labels);
        DistributionSummary.builder(metricName)
                .description("Kafka message size in bytes")
                .tags(tags)
                .baseUnit("bytes")
                .register(meterRegistry)
                .record(sizeBytes);

        cardinalityEnforcer.recordMetric(metricName, labels);
    }

    private Tags createTags(String... labels) {
        Tags tags = Tags.of(
                MetricLabels.Common.SERVICE, config.getServiceName(),
                MetricLabels.Common.REGION, config.getRegion(),
                MetricLabels.Common.ENVIRONMENT, config.getEnvironment()
        );

        for (int i = 0; i < labels.length - 1; i += 2) {
            tags = tags.and(labels[i], labels[i + 1]);
        }

        return tags;
    }
}
