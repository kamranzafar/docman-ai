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

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.wf.DocumentActivities;
import org.kamranzafar.docman.wf.DocumentWorkflow;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

@Slf4j
@Service
@WorkflowImpl(taskQueues = "documents")
public class DocumentWorkflowImpl implements DocumentWorkflow {
    private static final Duration UPLOAD_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration MAX_UPLOAD_WAIT = Duration.ofMinutes(15);

    private final Supplier<DocumentActivities> activities;
    private final Supplier<DocumentActivities> uploadCheckActivities;

    public DocumentWorkflowImpl() {
        this.activities = () -> Workflow.newActivityStub(
                DocumentActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(300))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setMaximumAttempts(3)
                                .setInitialInterval(Duration.ofSeconds(1))
                                .build())
                        .build()
        );
        // checkUploadStatus is a single, fast poll now (no in-activity loop), so it
        // gets its own short-timeout stub rather than parking a worker thread for
        // the entire upload wait window.
        this.uploadCheckActivities = () -> Workflow.newActivityStub(
                DocumentActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setMaximumAttempts(3)
                                .setInitialInterval(Duration.ofSeconds(1))
                                .build())
                        .build()
        );
    }

    @Override
    public void processDocument(Document document) {
        DocumentActivities activity = activities.get();

        try {
            activity.notify(document.getId().toString(), "Document Created");

            waitForUpload(document);

            document.setStatus(DocumentStatus.UPLOADED.name());

            Async.function(() -> {
                activity.update(document);
                return null;
            }).get();

            activity.notify(document.getId().toString(), "Document Content Uploaded");

            Async.function(() -> {
                activity.index(document);
                return null;
            }).get();

            activity.notify(document.getId().toString(), "Document Indexed");
        } catch (RuntimeException e) {
            document.setStatus(DocumentStatus.FAILED.name());
            activity.update(document);
            activity.notify(document.getId().toString(), "Document Processing Failed: " + e.getMessage());
            throw e;
        }
    }

    private void waitForUpload(Document document) {
        DocumentActivities uploadCheckActivity = uploadCheckActivities.get();
        long maxAttempts = MAX_UPLOAD_WAIT.toMillis() / UPLOAD_POLL_INTERVAL.toMillis();

        for (long attempt = 0; attempt < maxAttempts; attempt++) {
            if (uploadCheckActivity.checkUploadStatus(document)) {
                return;
            }
            Workflow.sleep(UPLOAD_POLL_INTERVAL);
        }

        throw ApplicationFailure.newNonRetryableFailure(
                "Document content was not uploaded within " + MAX_UPLOAD_WAIT, "UploadTimeout");
    }
}
