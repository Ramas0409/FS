package com.fraudswitch.metrics.config;

import com.fraudswitch.metrics.cardinality.CardinalityEnforcer;
import com.fraudswitch.metrics.common.KafkaMetrics;
import com.fraudswitch.metrics.common.RequestMetrics;
import com.fraudswitch.metrics.services.FraudRouterMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for Fraud Switch Metrics Library.
 * 
 * <p>This configuration is automatically activated when:
 * 1. Spring Boot Actuator is on the classpath
 * 2. Micrometer is on the classpath
 * 3. fraud-switch.metrics.enabled=true (default)
 * 
 * <p>Provides the following beans:
 * - MetricsConfigurationProperties
 * - CardinalityEnforcer
 * - RequestMetrics (if service not specified)
 * - KafkaMetrics (if service not specified)
 * - Service-specific metrics (e.g., FraudRouterMetrics)
 * 
 * <p>Usage in consuming service:
 * <pre>
 * &#64;Autowired
 * private FraudRouterMetrics metrics;
 * 
 * public void handleRequest() {
 *     Timer.Sample sample = metrics.getRequestMetrics().startTimer();
 *     try {
 *         // ... handle request ...
 *         long duration = sample.stop(metrics.getRequestMetrics().getRequestTimer());
 *         metrics.recordRoutingDecision("auth", "stripe", "fraud_sight", 
 *                                       "Ravelin", "primary", duration);
 *     } catch (Exception e) {
 *         long duration = sample.stop(metrics.getRequestMetrics().getRequestTimer());
 *         metrics.getRequestMetrics().recordError(duration, e.getClass().getSimpleName());
 *     }
 * }
 * </pre>
 * 
 * @version 1.0.0
 * @since 2025-10-14
 */
@Slf4j
@AutoConfiguration(after = CompositeMeterRegistryAutoConfiguration.class)
@ConditionalOnClass({MeterRegistry.class})
@ConditionalOnProperty(prefix = "fraud-switch.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MetricsConfigurationProperties.class)
public class MetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CardinalityEnforcer cardinalityEnforcer(MetricsConfigurationProperties config) {
        log.info("Initializing CardinalityEnforcer with enforcement: {}", 
                config.getCardinality().isEnforcementEnabled());
        return new CardinalityEnforcer(config);
    }

    /**
     * Configuration for common metrics (when no service-specific bean exists).
     */
    @Configuration
    @ConditionalOnMissingBean(name = "serviceSpecificMetrics")
    public static class CommonMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public RequestMetrics requestMetrics(
                MeterRegistry meterRegistry,
                CardinalityEnforcer cardinalityEnforcer,
                MetricsConfigurationProperties config) {
            
            log.info("Initializing RequestMetrics for service: {}", config.getServiceName());
            return new RequestMetrics(
                    meterRegistry,
                    cardinalityEnforcer,
                    config,
                    "fraud_switch." + config.getServiceName()
            );
        }

        @Bean
        @ConditionalOnMissingBean
        public KafkaMetrics kafkaMetrics(
                MeterRegistry meterRegistry,
                CardinalityEnforcer cardinalityEnforcer,
                MetricsConfigurationProperties config) {
            
            log.info("Initializing KafkaMetrics for service: {}", config.getServiceName());
            return new KafkaMetrics(
                    meterRegistry,
                    cardinalityEnforcer,
                    config,
                    "fraud_switch." + config.getServiceName()
            );
        }
    }

    /**
     * Configuration for Fraud Router service-specific metrics.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "fraud-switch.metrics", name = "service-name", havingValue = "fraud-router")
    public static class FraudRouterMetricsConfiguration {

        @Bean(name = "serviceSpecificMetrics")
        @ConditionalOnMissingBean
        public FraudRouterMetrics fraudRouterMetrics(
                MeterRegistry meterRegistry,
                CardinalityEnforcer cardinalityEnforcer,
                MetricsConfigurationProperties config) {
            
            log.info("Initializing FraudRouterMetrics for region: {}", config.getRegion());
            return new FraudRouterMetrics(meterRegistry, cardinalityEnforcer, config);
        }
    }

    /**
     * Configuration for Rules Service metrics.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "fraud-switch.metrics", name = "service-name", havingValue = "rules-service")
    public static class RulesServiceMetricsConfiguration {

        @Bean(name = "serviceSpecificMetrics")
        @ConditionalOnMissingBean
        public RequestMetrics rulesServiceMetrics(
                MeterRegistry meterRegistry,
                CardinalityEnforcer cardinalityEnforcer,
                MetricsConfigurationProperties config) {
            
            log.info("Initializing RulesServiceMetrics for region: {}", config.getRegion());
            // Rules Service uses common RequestMetrics as base
            // Service-specific metrics can be added later
            return new RequestMetrics(
                    meterRegistry,
                    cardinalityEnforcer,
                    config,
                    "fraud_switch.rules_service"
            );
        }
    }

    /**
     * Configuration for BIN Lookup Service metrics.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "fraud-switch.metrics", name = "service-name", havingValue = "bin-lookup-service")
    public static class BinLookupServiceMetricsConfiguration {

        @Bean(name = "serviceSpecificMetrics")
        @ConditionalOnMissingBean
        public RequestMetrics binLookupServiceMetrics(
                MeterRegistry meterRegistry,
                CardinalityEnforcer cardinalityEnforcer,
                MetricsConfigurationProperties config) {
            
            log.info("Initializing BinLookupServiceMetrics for region: {}", config.getRegion());
            return new RequestMetrics(
                    meterRegistry,
                    cardinalityEnforcer,
                    config,
                    "fraud_switch.bin_lookup_service"
            );
        }
    }

    /**
     * Configuration for FraudSight Adapter metrics.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "fraud-switch.metrics", name = "service-name", havingValue = "fraudsight-adapter")
    public static class FraudSightAdapterMetricsConfiguration {

        @Bean(name = "serviceSpecificMetrics")
        @ConditionalOnMissingBean
        public RequestMetrics fraudSightAdapterMetrics(
                MeterRegistry meterRegistry,
                CardinalityEnforcer cardinalityEnforcer,
                MetricsConfigurationProperties config) {
            
            log.info("Initializing FraudSightAdapterMetrics for region: {}", config.getRegion());
            return new RequestMetrics(
                    meterRegistry,
                    cardinalityEnforcer,
                    config,
                    "fraud_switch.fraudsight_adapter"
            );
        }
    }

    /**
     * Configuration for GuaranteedPayment Adapter metrics.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "fraud-switch.metrics", name = "service-name", havingValue = "guaranteed-payment-adapter")
    public static class GuaranteedPaymentAdapterMetricsConfiguration {

        @Bean(name = "serviceSpecificMetrics")
        @ConditionalOnMissingBean
        public RequestMetrics guaranteedPaymentAdapterMetrics(
                MeterRegistry meterRegistry,
                CardinalityEnforcer cardinalityEnforcer,
                MetricsConfigurationProperties config) {
            
            log.info("Initializing GuaranteedPaymentAdapterMetrics for region: {}", config.getRegion());
            return new RequestMetrics(
                    meterRegistry,
                    cardinalityEnforcer,
                    config,
                    "fraud_switch.guaranteed_payment_adapter"
            );
        }
    }

    /**
     * Configuration for Async Processor metrics.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "fraud-switch.metrics", name = "service-name", havingValue = "async-processor")
    public static class AsyncProcessorMetricsConfiguration {

        @Bean(name = "serviceSpecificMetrics")
        @ConditionalOnMissingBean
        public KafkaMetrics asyncProcessorMetrics(
                MeterRegistry meterRegistry,
                CardinalityEnforcer cardinalityEnforcer,
                MetricsConfigurationProperties config) {
            
            log.info("Initializing AsyncProcessorMetrics for region: {}", config.getRegion());
            // Async Processor primarily uses Kafka metrics
            return new KafkaMetrics(
                    meterRegistry,
                    cardinalityEnforcer,
                    config,
                    "fraud_switch.async_processor"
            );
        }
    }

    /**
     * Configuration for Tokenization Service metrics.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "fraud-switch.metrics", name = "service-name", havingValue = "tokenization-service")
    public static class TokenizationServiceMetricsConfiguration {

        @Bean(name = "serviceSpecificMetrics")
        @ConditionalOnMissingBean
        public KafkaMetrics tokenizationServiceMetrics(
                MeterRegistry meterRegistry,
                CardinalityEnforcer cardinalityEnforcer,
                MetricsConfigurationProperties config) {
            
            log.info("Initializing TokenizationServiceMetrics for region: {}", config.getRegion());
            return new KafkaMetrics(
                    meterRegistry,
                    cardinalityEnforcer,
                    config,
                    "fraud_switch.tokenization_service"
            );
        }
    }

    /**
     * Configuration for Issuer Data Service metrics.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "fraud-switch.metrics", name = "service-name", havingValue = "issuer-data-service")
    public static class IssuerDataServiceMetricsConfiguration {

        @Bean(name = "serviceSpecificMetrics")
        @ConditionalOnMissingBean
        public RequestMetrics issuerDataServiceMetrics(
                MeterRegistry meterRegistry,
                CardinalityEnforcer cardinalityEnforcer,
                MetricsConfigurationProperties config) {
            
            log.info("Initializing IssuerDataServiceMetrics for region: {}", config.getRegion());
            return new RequestMetrics(
                    meterRegistry,
                    cardinalityEnforcer,
                    config,
                    "fraud_switch.issuer_data_service"
            );
        }
    }
}
