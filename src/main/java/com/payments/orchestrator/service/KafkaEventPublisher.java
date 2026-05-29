package com.payments.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Profile("prod")
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    @Override
    public void publish(String topic, String key, Map<String, Object> payload) {
        // Stubbed production Kafka Publisher simulation
        log.info("[KAFKA BROKER PUBLISH] Topic: '{}', Partition Key: '{}', Payload: {}", topic, key, payload);
    }
}
