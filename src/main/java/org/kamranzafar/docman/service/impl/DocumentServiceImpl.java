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
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.repository.mongo.DocumentMetadataRepository;
import org.kamranzafar.docman.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class DocumentServiceImpl implements DocumentService {
    @Autowired
    private DocumentMetadataRepository documentMetadataRepository;

    @Transactional
    @Override
    public Document create(Document document) {
        document.setId(UUID.randomUUID());

        log.info("Creating a new document with id {}", document.getId());

        return saveDocument(document, DocumentStatus.CREATED.name());
    }

    @Transactional
    @Override
    public Document update(Document document) {
        log.info("Updating a document with id {}", document.getId());
        return saveDocument(document, DocumentStatus.UPDATED.name());
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
    public Document delete(Document document) {
        log.info("Deleting document with id {}", document.getId());
        return null;
    }

    @Override
    public Document findMetadata(UUID id) {
        log.info("Finding document metadata with id {}", id);
        Optional<Document> op = documentMetadataRepository.findById(id);
        if (op.isEmpty()) {
            throw new RuntimeException("Document not found");
        }

        return op.get();
    }
}
