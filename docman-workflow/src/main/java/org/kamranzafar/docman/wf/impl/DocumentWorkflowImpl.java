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
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.model.DocumentType;
import org.kamranzafar.docman.model.QueryConstants;
import org.kamranzafar.docman.wf.DocumentActivities;
import org.kamranzafar.docman.wf.DocumentWorkflow;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
@WorkflowImpl(taskQueues = "documents")
public class DocumentWorkflowImpl implements DocumentWorkflow {
    private static final Duration UPLOAD_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration MAX_UPLOAD_WAIT = Duration.ofMinutes(15);

    private final Supplier<DocumentActivities> activities;
    private final Supplier<DocumentActivities> uploadCheckActivities;
    private final Supplier<DocumentActivities> summaryActivities;
    private final Supplier<DocumentActivities> classificationActivities;

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
        // Summary generation is an LLM call that can legitimately take minutes on
        // CPU-only inference, so it gets a long timeout and a single attempt -
        // retrying a slow-but-working call would just repeat the wait for no benefit.
        this.summaryActivities = () -> Workflow.newActivityStub(
                DocumentActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMinutes(10))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setMaximumAttempts(1)
                                .build())
                        .build()
        );
        // Classification is also an LLM call over the full document text, so it gets
        // the same long-timeout, single-attempt treatment as summary generation.
        this.classificationActivities = () -> Workflow.newActivityStub(
                DocumentActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMinutes(10))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setMaximumAttempts(1)
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

            Promise<Void> indexPromise = Async.function(() -> {
                activity.index(document);
                return null;
            });

            indexPromise.get();
            document.setStatus(DocumentStatus.INDEXED.name());

            // Summary and classification both query the vector store for this
            // document's own chunks, so neither can start until indexing has written
            // them - they run concurrently with each other instead of with indexing.
            Promise<String> summaryPromise = Async.function(() -> summaryActivities.get().generateSummary(document));
            Promise<String> classificationPromise = Async.function(() -> classificationActivities.get().classifyDocument(document));

            try {
                String summary = summaryPromise.get();
                if (summary != null && !summary.isBlank()) {
                    Map<String, Object> metadata = document.getMetadata() != null
                            ? new HashMap<>(document.getMetadata()) : new HashMap<>();
                    metadata.put(QueryConstants.SUMMARY_METADATA_KEY, summary);
                    document.setMetadata(metadata);
                }
            } catch (RuntimeException e) {
                // Summary generation is a supplementary enhancement, not core to
                // ingestion succeeding - don't fail the whole document over it.
                log.warn("Summary generation failed for document {}: {}", document.getId(), e.getMessage());
                activity.notify(document.getId().toString(), "Document Summary Generation Failed: " + e.getMessage());
            }

            activity.update(document);
            activity.notify(document.getId().toString(), "Document Indexed");

            try {
                String documentType = classificationPromise.get();
                document.setDocumentType(documentType != null && !documentType.isBlank()
                        ? documentType : DocumentType.UNKNOWN.getLabel());
            } catch (RuntimeException e) {
                // Classification is a supplementary enhancement, not core to
                // ingestion succeeding - don't fail the whole document over it.
                log.warn("Classification failed for document {}: {}", document.getId(), e.getMessage());
                document.setDocumentType(DocumentType.UNKNOWN.getLabel());
            }

            activity.update(document);
            activity.notify(document.getId().toString(), "Document Classified: " + document.getDocumentType());
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
