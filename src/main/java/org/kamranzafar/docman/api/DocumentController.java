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

import org.kamranzafar.docman.model.*;
import org.kamranzafar.docman.service.DocumentService;
import org.kamranzafar.docman.service.PresignedUrlService;
import org.kamranzafar.docman.wf.DocumentWorkflowManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/document")
public class DocumentController {
    @Autowired
    private DocumentService documentService;
    @Autowired
    private PresignedUrlService presignedUrlService;
    @Autowired
    private DocumentWorkflowManager documentWorkflowManager;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody DocumentRequest documentRequest) {
        Document document = new Document();
        document.setName(documentRequest.getName());
        document.setContentType(documentRequest.getContentType());

        Map<String, Object> metadataMap = documentRequest.getMetadata() == null
                ? new HashMap<>() : new HashMap<>(documentRequest.getMetadata());

        document.setMetadata(metadataMap);
        document = documentService.create(document);

        String url = presignedUrlService.uploadUrl(document);
        DocumentResponse documentResponse = new DocumentResponse();
        documentResponse.setUrl(url);
        documentResponse.setDocument(document);

        //documentWorkflowManager.createWorkflow(document);

        return ResponseEntity.ok(documentResponse);
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody DocumentSearchRequest request) {
        DocumentSearchResponse response = DocumentSearchResponse.builder().build();
        response.setAnswer(documentService.ask(request.getQuestion()));

        return ResponseEntity.ok(response);
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody DocumentSearchRequest request) {
        return ResponseEntity.ok(documentService.search(request.getQuery()));
    }

    @GetMapping("/metadata")
    public ResponseEntity<?> getMetadata(@RequestBody DocumentSearchRequest request) {
        DocumentSearchResponse response = DocumentSearchResponse.builder().build();
        response.setDocuments(Collections.singletonList(documentService.findMetadata(UUID.fromString(request.getId()))));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/content")
    public ResponseEntity<?> getContent(@RequestBody DocumentSearchRequest request) {
        Document document = documentService.findMetadata(UUID.fromString(request.getId()));
        String url = presignedUrlService.downloadUrl(document);

        DocumentResponse documentResponse = new DocumentResponse();
        documentResponse.setUrl(url);

        return ResponseEntity.ok(documentResponse);
    }
}
