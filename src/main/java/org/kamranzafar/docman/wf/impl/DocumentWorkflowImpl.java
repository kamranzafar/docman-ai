package org.kamranzafar.docman.wf.impl;

import io.micrometer.common.util.StringUtils;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.wf.DocumentActivities;
import org.kamranzafar.docman.wf.DocumentWorkflow;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

@Slf4j
@Service
@WorkflowImpl(taskQueues = "documents")
public class DocumentWorkflowImpl implements DocumentWorkflow {
    private final Supplier<DocumentActivities> activities;

    public DocumentWorkflowImpl() {
        this.activities = () -> Workflow.newActivityStub(
                DocumentActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setMaximumAttempts(3)
                                .setInitialInterval(Duration.ofSeconds(1))
                                .build())
                        .build()
        );
    }

    @Override
    public void processDocument(Document document) {
        var activity = activities.get();

        String extractedData =
                Async.function(() -> activity.extract(document.getId().toString())).get();

        if (!StringUtils.isBlank(extractedData)) {
            Async.function(() -> {
                activity.index(document.getId().toString(), extractedData);
                return null;
            }).get();
        }

        activity.notify(document.getId().toString(), "Success");
    }
}
