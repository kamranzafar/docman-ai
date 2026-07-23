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
import org.kamranzafar.docman.model.Document;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
public class DocumentWorkflowManager {
    private final WorkflowClient workflowClient;

    public DocumentWorkflowManager(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public WorkflowExecution createWorkflow(Document document) {
        DocumentWorkflow workflow =
                workflowClient.newWorkflowStub(
                        DocumentWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(workflowId(document.getId()))
                                .setTaskQueue("documents")
                                .build()
                );

        return WorkflowClient.start(workflow::processDocument, document);
    }

    public DocumentWorkflow getWorkflow(String id) {
        return workflowClient.newWorkflowStub(DocumentWorkflow.class, id);
    }

    public void terminateWorkflow(UUID documentId) {
        try {
            WorkflowStub.fromTyped(getWorkflow(workflowId(documentId)))
                    .terminate("Document deleted");
        } catch (WorkflowNotFoundException e) {
            log.debug("No workflow found for document {} (already completed or never started)", documentId);
        }
    }

    private String workflowId(UUID documentId) {
        return String.format("doc-wf-%s", documentId);
    }
}
