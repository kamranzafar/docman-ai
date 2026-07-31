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
import org.kamranzafar.docman.exception.DocumentNotFoundException;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/v1/document")
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
                                    @RequestPart Map<String, Object> metadata,
                                    @RequestPart(value = "createdBy", required = false) String createdBy) {
        DocumentRequest documentRequest = new DocumentRequest();
        documentRequest.setName(file.getOriginalFilename());
        documentRequest.setContentType(file.getContentType());
        documentRequest.setMetadata(metadata);
        documentRequest.setCreatedBy(createdBy);

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

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                     @RequestPart("metadata") DocumentUpdateRequest updateRequest,
                                     @RequestPart(value = "file", required = false) MultipartFile file) {
        UUID documentId = parseId(id);

        boolean fileIncluded = file != null && !file.isEmpty();
        boolean hasMetadataChange = updateRequest.getMetadata() != null
                || StringUtils.hasText(updateRequest.getDocumentType());
        if (!fileIncluded && !hasMetadataChange) {
            throw new DocmanException("Update request must include metadata, documentType, or a file");
        }

        DocumentDto document = documentService.updateDocument(
                documentId,
                updateRequest,
                fileIncluded ? file.getOriginalFilename() : null,
                fileIncluded ? file.getContentType() : null);

        if (fileIncluded) {
            // A new file is a new version's content - clear the previous version's
            // chunks before the new version's workflow indexes fresh ones, so search/RAG
            // never mixes content from two versions.
            documentIndexService.deleteIndex(document);
            try {
                objectStoreService.saveDocumentContent(document, file.getInputStream(), file.getSize());
            } catch (IOException e) {
                throw new DocmanException(e.getMessage(), e);
            }
            documentWorkflowManager.createWorkflow(document);
        }

        DocumentResponse documentResponse = new DocumentResponse();
        documentResponse.setDocument(document);

        return ResponseEntity.ok(documentResponse);
    }

    @PostMapping("/{id}")
    public ResponseEntity<?> updatePresigned(@PathVariable String id,
                                              @RequestBody DocumentUpdateRequest updateRequest) {
        UUID documentId = parseId(id);

        if (!StringUtils.hasText(updateRequest.getName()) || !StringUtils.hasText(updateRequest.getContentType())) {
            throw new DocmanException("name and contentType are mandatory for a presigned update");
        }

        DocumentDto document = documentService.updateDocument(
                documentId, updateRequest, updateRequest.getName(), updateRequest.getContentType());

        // Same reasoning as the multipart update: a new file version means the previous
        // version's chunks need clearing before the new version's workflow indexes fresh
        // ones. The client hasn't uploaded yet, but the workflow's upload-wait step will
        // pick up the content once it lands, exactly like POST /document.
        documentIndexService.deleteIndex(document);

        String url = objectStoreService.presignedUploadUrl(document);
        DocumentResponse documentResponse = new DocumentResponse();
        documentResponse.setUrl(url);
        documentResponse.setDocument(document);

        documentWorkflowManager.createWorkflow(document);

        return ResponseEntity.ok(documentResponse);
    }

    @PostMapping("/ask")
    public DeferredResult<ResponseEntity<?>> ask(@RequestBody DocumentSearchRequest request) {
        if (!StringUtils.hasText(request.getQuestion())) {
            throw new DocmanException("Question is mandatory");
        }
        if (request.getQuestion().length() > QueryConstants.QUERY_MAX_QUESTION_LENGTH) {
            throw new DocmanException(
                    "Question exceeds maximum length of " + QueryConstants.QUERY_MAX_QUESTION_LENGTH + " characters");
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

    @PostMapping("/search/hybrid")
    public ResponseEntity<?> hybridSearch(@RequestBody DocumentSearchRequest request) {
        if (!StringUtils.hasText(request.getQuery())) {
            throw new DocmanException("Query is mandatory");
        }
        if (request.getFilters() != null && request.getFilters().size() > QueryConstants.QUERY_MAX_FILTERS) {
            throw new DocmanException("Filters exceed maximum count of " + QueryConstants.QUERY_MAX_FILTERS);
        }

        return ResponseEntity.ok(documentSearchService.hybridSearch(request.getQuery(), request.getFilters()));
    }

    @GetMapping({"/metadata/{id}", "/metadata/{id}/{version}"})
    public ResponseEntity<?> getMetadata(@PathVariable String id,
                                          @PathVariable(required = false) Integer version) {
        DocumentDto document = version != null
                ? documentService.findMetadata(parseId(id), version)
                : documentService.findMetadata(parseId(id));

        DocumentSearchResponse response = DocumentSearchResponse.builder().build();
        response.setDocuments(Collections.singletonList(document));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/revisions/{id}")
    public ResponseEntity<?> getRevisions(@PathVariable String id) {
        List<DocumentDto> revisions = documentService.findRevisions(parseId(id));

        DocumentSearchResponse response = DocumentSearchResponse.builder().build();
        response.setDocuments(revisions);

        return ResponseEntity.ok(response);
    }

    @GetMapping({"/content/{id}", "/content/{id}/{version}"})
    public ResponseEntity<?> getContent(@PathVariable String id,
                                         @PathVariable(required = false) Integer version) {
        UUID documentId = parseId(id);
        DocumentDto document = version != null
                ? documentService.findMetadata(documentId, version)
                : documentService.findMetadata(documentId);

        if (version != null && !objectStoreService.documentExists(document)) {
            throw new DocumentNotFoundException("Document content not found for version " + version);
        }

        String url = objectStoreService.presignedDownloadUrl(document);

        DocumentResponse documentResponse = new DocumentResponse();
        documentResponse.setUrl(url);

        return ResponseEntity.ok(documentResponse);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        DocumentDto document = documentService.findMetadata(parseId(id));

        documentWorkflowManager.terminateWorkflow(document.getId(), document.getVersion());
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
