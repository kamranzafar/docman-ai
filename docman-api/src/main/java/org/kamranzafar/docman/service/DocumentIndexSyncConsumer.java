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

package org.kamranzafar.docman.service;

import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.exception.DocumentNotFoundException;
import org.kamranzafar.docman.model.DocumentDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Reacts to every {@link DocumentService#update} by syncing the updated metadata into
 * the document's already-indexed vector store chunks, closing the gap where indexing
 * captures metadata/documentType once and never sees later changes (e.g. classification
 * or summary results).
 */
@Slf4j
@Component
public class DocumentIndexSyncConsumer {
    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentIndexService documentIndexService;

    @KafkaListener(topics = "${kafka.metadata-sync-topic}", groupId = "docman-index-sync")
    public void onDocumentUpdated(String documentId) {
        try {
            DocumentDto document = documentService.findMetadata(UUID.fromString(documentId));
            documentIndexService.updateMetadata(document);
        } catch (DocumentNotFoundException e) {
            log.warn("Document {} no longer exists, skipping index metadata sync", documentId);
        }
    }
}
