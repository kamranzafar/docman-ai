package org.kamranzafar.docman.wf.impl;

import io.temporal.spring.boot.ActivityImpl;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.service.DocumentExtractService;
import org.kamranzafar.docman.service.DocumentIndexService;
import org.kamranzafar.docman.service.DocumentService;
import org.kamranzafar.docman.wf.DocumentActivities;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ActivityImpl(taskQueues = "documents")
public class DocumentActivitiesImpl implements DocumentActivities {
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private DocumentExtractService documentExtractService;
    @Autowired
    private DocumentIndexService documentIndexService;
    @Autowired
    private DocumentService documentService;

    @Override
    public Document create(Document document) {
        return documentService.create(document);
    }

    @Override
    public Document update(Document document) {
        return documentService.update(document);
    }

    @Override
    public Document index(String documentId, String content) {
        return documentIndexService.index(documentId, content);
    }

    @Override
    public String extract(String documentId) {
        return documentExtractService.extract(documentService.findContent(UUID.fromString(documentId)));
    }

    @Override
    public void notify(String documentId, String msg) {
        kafkaTemplate.send("documents", String.format("Document %s: %s", documentId, msg));
    }
}
