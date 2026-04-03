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
