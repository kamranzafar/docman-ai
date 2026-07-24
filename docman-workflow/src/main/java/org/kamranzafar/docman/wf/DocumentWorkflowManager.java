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

package org.kamranzafar.docman.wf;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.mapper.DocumentMapper;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.model.DocumentDto;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class DocumentWorkflowManager {
    private final WorkflowClient workflowClient;
    private final DocumentMapper documentMapper;

    public DocumentWorkflowManager(WorkflowClient workflowClient, DocumentMapper documentMapper) {
        this.workflowClient = workflowClient;
        this.documentMapper = documentMapper;
    }

    public WorkflowExecution createWorkflow(DocumentDto document) {
        DocumentWorkflow workflow =
                workflowClient.newWorkflowStub(
                        DocumentWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(workflowId(document.getId(), document.getVersion()))
                                .setTaskQueue("documents")
                                .build()
                );

        Document entity = documentMapper.toEntity(document);
        return WorkflowClient.start(workflow::processDocument, entity);
    }

    public DocumentWorkflow getWorkflow(String id) {
        return workflowClient.newWorkflowStub(DocumentWorkflow.class, id);
    }

    public void terminateWorkflow(UUID documentId, int version) {
        try {
            WorkflowStub.fromTyped(getWorkflow(workflowId(documentId, version)))
                    .terminate("Document deleted");
        } catch (WorkflowNotFoundException e) {
            log.debug("No workflow found for document {} version {} (already completed or never started)",
                    documentId, version);
        }
    }

    private String workflowId(UUID documentId, int version) {
        return String.format("doc-wf-%s-v%d", documentId, version);
    }
}
