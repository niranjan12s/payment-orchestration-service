package com.payments.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Profile({"local", "test"})
public class InMemoryEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventPublisher.class);

    private final List<PublishedEventRecord> publishedEvents = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String topic, String key, Map<String, Object> payload) {
        log.info("[In-Memory Publisher] Publishing to topic '{}' with partition key '{}'. Event: {}", topic, key, payload);
        publishedEvents.add(new PublishedEventRecord(topic, key, payload));
    }

    public List<PublishedEventRecord> getPublishedEvents() {
        return new ArrayList<>(publishedEvents);
    }

    public void clear() {
        publishedEvents.clear();
    }

    public static record PublishedEventRecord(String topic, String key, Map<String, Object> payload) {}
}
