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

package org.kamranzafar.docman.api;

import jakarta.validation.Valid;
import org.kamranzafar.docman.exception.DocmanException;
import org.kamranzafar.docman.model.*;
import org.kamranzafar.docman.service.DocumentIndexService;
import org.kamranzafar.docman.service.DocumentSearchService;
import org.kamranzafar.docman.service.DocumentService;
import org.kamranzafar.docman.service.ObjectStoreService;
import org.kamranzafar.docman.wf.DocumentWorkflowManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/document")
public class DocumentController {
    private static final long ASK_TIMEOUT_MS = 650_000;

    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentSearchService documentSearchService;
    @Autowired
    private ObjectStoreService objectStoreService;
    @Autowired
    private DocumentIndexService documentIndexService;
    @Autowired
    private DocumentWorkflowManager documentWorkflowManager;
    @Autowired
    @Qualifier("askExecutor")
    private Executor askExecutor;

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody DocumentRequest documentRequest) {
        DocumentDto document = documentService.create(documentRequest);

        String url = objectStoreService.presignedUploadUrl(document);
        DocumentResponse documentResponse = new DocumentResponse();
        documentResponse.setUrl(url);
        documentResponse.setDocument(document);

        documentWorkflowManager.createWorkflow(document);

        return ResponseEntity.ok(documentResponse);
    }


    @PutMapping
    public ResponseEntity<?> create(@RequestPart("file") MultipartFile file,
                                    @RequestPart Map<String, Object> metadata) {
        DocumentRequest documentRequest = new DocumentRequest();
        documentRequest.setName(file.getOriginalFilename());
        documentRequest.setContentType(file.getContentType());
        documentRequest.setMetadata(metadata);

        DocumentDto document = documentService.create(documentRequest);

        try {
            objectStoreService.saveDocumentContent(document, file.getInputStream(), file.getSize());
        } catch (IOException e) {
            throw new DocmanException(e.getMessage(), e);
        }

        DocumentResponse documentResponse = new DocumentResponse();
        documentResponse.setDocument(document);

        documentWorkflowManager.createWorkflow(document);

        return ResponseEntity.ok(documentResponse);
    }

    @PostMapping("/ask")
    public DeferredResult<ResponseEntity<?>> ask(@RequestBody DocumentSearchRequest request) {
        if (!StringUtils.hasText(request.getQuestion())) {
            throw new DocmanException("Question is mandatory");
        }

        DeferredResult<ResponseEntity<?>> deferredResult = new DeferredResult<>(ASK_TIMEOUT_MS);
        deferredResult.onTimeout(() -> deferredResult.setErrorResult(
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Question answering timed out")));

        askExecutor.execute(() -> {
            try {
                DocumentSearchResponse response = DocumentSearchResponse.builder().build();
                response.setAnswer(documentSearchService.vectorSearch(request.getQuestion()));
                deferredResult.setResult(ResponseEntity.ok(response));
            } catch (RuntimeException e) {
                deferredResult.setErrorResult(e);
            }
        });

        return deferredResult;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody DocumentSearchRequest request) {
        if (request.getFilters() == null || request.getFilters().isEmpty()) {
            throw new DocmanException("At least one metadata filter is mandatory");
        }
        if (request.getFilters().size() > QueryConstants.QUERY_MAX_FILTERS) {
            throw new DocmanException("Filters exceed maximum count of " + QueryConstants.QUERY_MAX_FILTERS);
        }

        return ResponseEntity.ok(documentSearchService.lexicalSearch(request.getFilters()));
    }

    @GetMapping("/metadata")
    public ResponseEntity<?> getMetadata(@RequestParam String id) {
        DocumentSearchResponse response = DocumentSearchResponse.builder().build();
        response.setDocuments(Collections.singletonList(documentService.findMetadata(parseId(id))));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/content")
    public ResponseEntity<?> getContent(@RequestParam String id) {
        DocumentDto document = documentService.findMetadata(parseId(id));
        String url = objectStoreService.presignedDownloadUrl(document);

        DocumentResponse documentResponse = new DocumentResponse();
        documentResponse.setUrl(url);

        return ResponseEntity.ok(documentResponse);
    }

    @DeleteMapping
    public ResponseEntity<?> delete(@RequestParam String id) {
        DocumentDto document = documentService.findMetadata(parseId(id));

        documentWorkflowManager.terminateWorkflow(document.getId());
        documentIndexService.deleteIndex(document);
        objectStoreService.deleteDocumentContent(document);
        documentService.delete(document);

        return ResponseEntity.noContent().build();
    }

    private UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new DocmanException("Invalid document id: " + id);
        }
    }
}
