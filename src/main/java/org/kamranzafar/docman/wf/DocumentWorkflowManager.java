package org.kamranzafar.docman.wf;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.kamranzafar.docman.model.Document;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
                                .setWorkflowId(String.format("doc-wf-%s", UUID.randomUUID()))
                                .setTaskQueue("documents")
                                .build()
                );

        return WorkflowClient.start(workflow::processDocument, document);
    }

    public DocumentWorkflow getWorkflow(String id) {
        return workflowClient.newWorkflowStub(DocumentWorkflow.class, id);
    }
}
