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
