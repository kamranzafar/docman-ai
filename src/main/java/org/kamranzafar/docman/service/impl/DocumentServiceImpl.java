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

package org.kamranzafar.docman.service.impl;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.jetbrains.annotations.NotNull;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.repository.mongo.DocumentMetadataRepository;
import org.kamranzafar.docman.service.DocumentService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentServiceImpl implements DocumentService {
    @Autowired
    private MinioClient minioClient;
    @Autowired
    private DocumentMetadataRepository documentMetadataRepository;
    @Autowired
    private VectorStore vectorStore;

    private final ChatClient chatClient;

    public DocumentServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Transactional
    @Override
    public Document create(Document document) {
        document.setId(UUID.randomUUID());
        return saveDocument(document, DocumentStatus.CREATED.name());
    }

    @Transactional
    @Override
    public Document update(Document document) {
        return saveDocument(document, DocumentStatus.CREATED.name());
    }

    @NotNull
    private Document saveDocument(Document document, String status) {
        if (document.getData() == null) {
            return document;
        }

        try {
            var byteInputStream = new ByteArrayInputStream(document.getData());

            minioClient.putObject(PutObjectArgs.builder()
                    .bucket("docman")
                    .object(document.getId() + "/" + document.getName())
                    .contentType(document.getContentType())
                    .stream(byteInputStream, byteInputStream.available(), -1)
                    .build());

            document.setStatus(status);

            documentMetadataRepository.save(document);

            return document;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Transactional
    @Override
    public Document delete(Document document) {
        return null;
    }

    @Override
    public Document findMetadata(UUID id) {
        Optional<Document> op = documentMetadataRepository.findById(id);
        if (op.isEmpty()) {
            throw new RuntimeException("Document not found");
        }

        return op.get();
    }

    @Override
    public Document findContent(UUID id) {
        Optional<Document> op = documentMetadataRepository.findById(id);
        if (op.isEmpty()) {
            throw new RuntimeException("Document not found");
        }

        Document document = op.get();

        lookupDocument(document);

        return document;
    }

    @Override
    public String ask(String question) {
        ChatResponse response = chatClient.prompt()
                .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .user(question)
                .call()
                .chatResponse();

        if (response != null) {
            return response.getResult().getOutput().getText();
        }

        return null;
    }

    private void lookupDocument(Document document) {
        try {
            document.setData(minioClient.getObject(GetObjectArgs.builder()
                    .bucket("docman")
                    .object(document.getId() + "/" + document.getName())
                    .build()).readAllBytes());
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
