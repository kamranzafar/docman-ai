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

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.service.DocumentIndexService;
import org.kamranzafar.docman.service.DocumentService;
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

    @Override
    public Document create(Document document) {
        return documentService.create(document);
    }

    @Override
    public Document update(Document document) {
        return documentService.update(document);
    }

    @Override
    public boolean checkUploadStatus(Document document) {
        while (true) {
            if (objectStoreService.documentExists(document)) {
                return true;
            }

            Activity.getExecutionContext().heartbeat(document);

            try {
                // Sleep for the poll interval
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw Activity.wrap(e);
            }
        }
    }

    @Override
    public void index(Document document) {
        documentIndexService.index(document);
    }

    @Override
    public void notify(String documentId, String msg) {
        kafkaTemplate.send("documents", String.format("Document %s: %s", documentId, msg));
    }
}
