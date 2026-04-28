package com.docsearch.config;

import com.docsearch.dto.DocumentEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ===================== TOPICS =====================

    @Bean
    public NewTopic documentEventsTopic() {
        return TopicBuilder.name("document-events")
                .partitions(12)
                .replicas(1)
                .config("retention.ms", "604800000")
                .config("min.insync.replicas", "1")
                .build();
    }

    @Bean
    public NewTopic documentEventsDltTopic() {
        return TopicBuilder.name("document-events.DLT")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", "2592000000")
                .build();
    }

    // ===================== PRODUCER =====================

    @Bean
    public ProducerFactory<String, DocumentEvent.DocumentIndexEvent> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Durability: wait for all ISR replicas to ack
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000);

        // Throughput: batch small writes
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);

        // Prevent duplicate messages on retry
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

//        // Compression for network efficiency
//        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, DocumentEvent.DocumentIndexEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ===================== CONSUMER =====================

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DocumentEvent.DocumentIndexEvent>
    kafkaListenerContainerFactory(ConsumerFactory<String, DocumentEvent.DocumentIndexEvent> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, DocumentEvent.DocumentIndexEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);

        // 4 concurrent consumers per instance (tune per pod CPU)
        factory.setConcurrency(4);

        // Manual ACK after successful processing — prevents message loss
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        return factory;
    }
}
