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

package org.kamranzafar.docman.wf.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.spring.boot.ActivityImpl;
import org.kamranzafar.docman.mapper.DocumentMapper;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.model.DocumentNotification;
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.service.DocumentClassificationService;
import org.kamranzafar.docman.service.DocumentIndexService;
import org.kamranzafar.docman.service.DocumentService;
import org.kamranzafar.docman.service.DocumentSummaryService;
import org.kamranzafar.docman.service.ObjectStoreService;
import org.kamranzafar.docman.wf.DocumentActivities;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@ActivityImpl(taskQueues = "documents")
public class DocumentActivitiesImpl implements DocumentActivities {
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private DocumentIndexService documentIndexService;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private ObjectStoreService objectStoreService;
    @Autowired
    private DocumentSummaryService documentSummaryService;
    @Autowired
    private DocumentClassificationService documentClassificationService;
    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public Document update(Document document) {
        return documentMapper.toEntity(documentService.update(documentMapper.toDto(document)));
    }

    @Override
    public boolean checkUploadStatus(Document document) {
        return objectStoreService.documentExists(documentMapper.toDto(document));
    }

    @Override
    public void index(Document document) {
        documentIndexService.index(documentMapper.toDto(document));
    }

    @Override
    public String generateSummary(Document document) {
        return documentSummaryService.generateSummary(documentMapper.toDto(document));
    }

    @Override
    public String classifyDocument(Document document) {
        return documentClassificationService.classify(documentMapper.toDto(document));
    }

    @Override
    public void notify(String documentId, DocumentStatus status, String errorMessage) {
        DocumentNotification notification = status == DocumentStatus.FAILED
                ? DocumentNotification.failed(documentId, errorMessage)
                : DocumentNotification.of(documentId, status);
        try {
            kafkaTemplate.send("documents", objectMapper.writeValueAsString(notification));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize document notification", e);
        }
    }
}
