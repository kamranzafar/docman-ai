package org.kamranzafar.docman.wf;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.kamranzafar.docman.model.Document;

@WorkflowInterface
public interface DocumentWorkflow {
    @WorkflowMethod
    void processDocument(Document document);
}
