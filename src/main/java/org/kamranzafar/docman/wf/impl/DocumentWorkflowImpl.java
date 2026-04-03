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
