package org.kamranzafar.docman.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.repository.opensearch.DocumentIndexRepository;
import org.kamranzafar.docman.repository.mongo.DocumentMetadataRepository;
import org.kamranzafar.docman.service.DocumentIndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class DocumentIndexServiceImpl implements DocumentIndexService {
    @Autowired
    DocumentMetadataRepository documentMetadataRepository;
    @Autowired
    DocumentIndexRepository documentIndexRepository;

    @Override
    public Document index(String documentId, String content) {
        Optional<Document> od = documentMetadataRepository.findById(UUID.fromString(documentId));

        if (od.isPresent()) {
            log.info("Indexing document with id {}", documentId);

            Document document = od.get();
            document.setStatus(DocumentStatus.INDEXED.name());

            documentMetadataRepository.save(document);
            documentIndexRepository.save(document);

            return document;
        }

        throw new RuntimeException("Document with id " + documentId + " not found");
    }
}
