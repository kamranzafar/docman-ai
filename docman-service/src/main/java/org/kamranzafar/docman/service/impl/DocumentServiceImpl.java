/**
 *
 * Copyright 2026 Kamran Zafar
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kamranzafar.docman.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.kamranzafar.docman.exception.DocumentNotFoundException;
import org.kamranzafar.docman.mapper.DocumentMapper;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.model.DocumentDto;
import org.kamranzafar.docman.model.DocumentRequest;
import org.kamranzafar.docman.model.DocumentRevision;
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.model.DocumentUpdateRequest;
import org.kamranzafar.docman.repository.DocumentMetadataRepository;
import org.kamranzafar.docman.repository.DocumentRevisionRepository;
import org.kamranzafar.docman.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class DocumentServiceImpl implements DocumentService {
    @Value(value = "${kafka.metadata-sync-topic}")
    private String metadataSyncTopic;

    @Autowired
    private DocumentMetadataRepository documentMetadataRepository;
    @Autowired
    private DocumentRevisionRepository documentRevisionRepository;
    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Transactional
    @Override
    public DocumentDto create(DocumentRequest request) {
        Document document = documentMapper.toEntity(request);
        document.setId(UUID.randomUUID());
        if (document.getMetadata() == null) {
            document.setMetadata(new HashMap<>());
        }

        Instant now = Instant.now();
        document.setVersion(1);
        document.setCreatedAt(now);
        document.setCreatedBy(request.getCreatedBy());
        document.setUpdatedAt(now);
        document.setUpdatedBy(request.getCreatedBy());

        log.info("Creating a new document with id {}", document.getId());

        Document saved = saveDocument(document, DocumentStatus.CREATED.name());
        saveRevision(saved, true);

        return documentMapper.toDto(saved);
    }

    @Transactional
    @Override
    public DocumentDto update(DocumentDto documentDto) {
        log.info("Updating a document with id {}", documentDto.getId());
        Document document = documentMapper.toEntity(documentDto);
        String status = document.getStatus() != null ? document.getStatus() : DocumentStatus.UPDATED.name();
        Document saved = saveDocument(document, status);

        // Triggers an async metadata-only sync into the vector store's already-indexed
        // chunks (see DocumentIndexSyncConsumer), so summary/classification results and
        // any other metadata change are reflected there without a full reindex.
        kafkaTemplate.send(metadataSyncTopic, saved.getId().toString());

        return documentMapper.toDto(saved);
    }

    @Transactional
    @Override
    public DocumentDto updateDocument(UUID id, DocumentUpdateRequest request, String newFileName,
                                       String newFileContentType) {
        log.info("Updating document {} (user-driven, version bump)", id);

        Optional<Document> op = documentMetadataRepository.findById(id);
        if (op.isEmpty()) {
            throw new DocumentNotFoundException("Document not found");
        }

        Document document = op.get();
        boolean fileIncluded = StringUtils.hasText(newFileName);

        document.setVersion(document.getVersion() + 1);
        document.setMetadata(request.getMetadata() != null ? new HashMap<>(request.getMetadata()) : new HashMap<>());
        if (StringUtils.hasText(request.getDocumentType())) {
            document.setDocumentType(request.getDocumentType());
        }
        document.setUpdatedAt(Instant.now());
        if (StringUtils.hasText(request.getUpdatedBy())) {
            document.setUpdatedBy(request.getUpdatedBy());
        }

        if (fileIncluded) {
            document.setName(newFileName);
            document.setContentType(newFileContentType);
            // A new file re-enters the same ingestion pipeline as a brand new document.
            document.setStatus(DocumentStatus.CREATED.name());
        }

        documentMetadataRepository.save(document);
        saveRevision(document, fileIncluded);

        // Skip the metadata-sync trigger when a file is included: the caller is about to
        // delete and rebuild this document's chunks from scratch via the new version's
        // workflow, so syncing now would be redundant - and worse, can race with that
        // deletion and fail with an OpenSearch version conflict on the same chunk.
        if (!fileIncluded) {
            kafkaTemplate.send(metadataSyncTopic, document.getId().toString());
        }

        return documentMapper.toDto(document);
    }

    private void saveRevision(Document document, boolean fileUpdated) {
        DocumentRevision revision = new DocumentRevision();
        revision.setDocumentId(document.getId());
        revision.setVersion(document.getVersion());
        revision.setName(document.getName());
        revision.setContentType(document.getContentType());
        revision.setDocumentType(document.getDocumentType());
        Map<String, Object> metadata = document.getMetadata();
        revision.setMetadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>());
        revision.setUpdatedAt(document.getUpdatedAt());
        revision.setUpdatedBy(document.getUpdatedBy());
        revision.setFileUpdated(fileUpdated);

        documentRevisionRepository.save(revision);
    }

    @NotNull
    private Document saveDocument(Document document, String status) {
        log.debug("Saving document with id {}", document.getId());

        document.setStatus(status);
        documentMetadataRepository.save(document);

        return document;
    }

    @Transactional
    @Override
    public void delete(DocumentDto document) {
        log.info("Deleting document with id {}", document.getId());
        documentMetadataRepository.deleteById(document.getId());
        documentRevisionRepository.deleteByDocumentId(document.getId());
    }

    @Override
    public DocumentDto findMetadata(UUID id) {
        log.info("Finding document metadata with id {}", id);
        Optional<Document> op = documentMetadataRepository.findById(id);
        if (op.isEmpty()) {
            throw new DocumentNotFoundException("Document not found");
        }

        return documentMapper.toDto(op.get());
    }

    @Override
    public DocumentDto findMetadata(UUID id, int version) {
        log.info("Finding document metadata with id {} at version {}", id, version);
        Optional<DocumentRevision> op = documentRevisionRepository.findByDocumentIdAndVersion(id, version);
        if (op.isEmpty()) {
            throw new DocumentNotFoundException("Document version not found");
        }

        DocumentRevision revision = op.get();
        return DocumentDto.builder()
                .id(revision.getDocumentId())
                .name(revision.getName())
                .contentType(revision.getContentType())
                .documentType(revision.getDocumentType())
                .metadata(revision.getMetadata())
                .updatedAt(revision.getUpdatedAt())
                .updatedBy(revision.getUpdatedBy())
                .version(revision.getVersion())
                .build();
    }
}
