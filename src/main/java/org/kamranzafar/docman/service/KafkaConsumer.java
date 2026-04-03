package org.kamranzafar.docman.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KafkaConsumer {
    @KafkaListener(topics = "documents", groupId = "docman")
    public void listenToDocumentEvent(String message) {
        log.info("Received Message in group docman: {}", message);
    }
}
