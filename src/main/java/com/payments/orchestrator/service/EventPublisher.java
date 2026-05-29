package com.payments.orchestrator.service;

import java.util.Map;

public interface EventPublisher {

    /**
     * Publishes an integration event to the specified downstream message broker topic.
     *
     * @param topic downstream destination topic (e.g. payment-events)
     * @param key aggregate ID for partitioning key ordering guarantees
     * @param payload key-value event payload
     */
    void publish(String topic, String key, Map<String, Object> payload);
}
