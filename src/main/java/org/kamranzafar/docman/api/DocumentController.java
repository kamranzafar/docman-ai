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

import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.model.DocumentSearchRequest;
import org.kamranzafar.docman.model.DocumentSearchResponse;
import org.kamranzafar.docman.service.DocumentService;
import org.kamranzafar.docman.wf.DocumentWorkflowManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/document")
public class DocumentController {
    @Autowired
    private DocumentService documentService;
    @Autowired
    private DocumentWorkflowManager documentWorkflowManager;

    @RequestMapping(method = RequestMethod.POST)
    public ResponseEntity<?> create(@RequestPart("file") MultipartFile file,
                                    @RequestPart Map<String, Object> metadata) {
        try {
            Document document = new Document();
            document.setData(file.getInputStream().readAllBytes());
            document.setName(file.getOriginalFilename());
            document.setContentType(file.getContentType());
            document.setMetadata(metadata);

            document = documentService.create(document);

            documentWorkflowManager.createWorkflow(document);

            return ResponseEntity.ok(document);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error uploading image");
        }
    }

    @GetMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody DocumentSearchRequest request) {
        DocumentSearchResponse response = DocumentSearchResponse.builder().build();
        response.setAnswer(documentService.ask(request.getQuestion()));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/metadata")
    public ResponseEntity<?> getMetadata(@RequestBody DocumentSearchRequest request) {
        DocumentSearchResponse response = DocumentSearchResponse.builder().build();
        response.setDocuments(Collections.singletonList(documentService.findMetadata(UUID.fromString(request.getId()))));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/content")
    public ResponseEntity<?> getContent(@RequestBody DocumentSearchRequest request) {
        Document document = documentService.findContent(UUID.fromString(request.getId()));
        InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(document.getData()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.getName() + "\"")
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .body(resource);
    }
}
