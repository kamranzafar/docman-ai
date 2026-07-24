/*
 *  Copyright 2026 Kamran Zafar
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.kamranzafar.docman.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.model.DocumentDto;
import org.kamranzafar.docman.model.DocumentType;
import org.kamranzafar.docman.model.QueryConstants;
import org.kamranzafar.docman.service.DocumentClassificationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentClassificationServiceImpl implements DocumentClassificationService {
    private static final String CLASSIFICATION_QUESTION = """
            What type of document is this? Respond with exactly one of these categories: %s.
            If the document does not clearly match any of these categories, respond with "unknown". \
            Respond with only the category name, no preamble or punctuation.""";

    // Classification picks a single category label, so a low temperature keeps
    // the answer stable instead of drifting between similar categories on
    // different runs, as happens at the app's default chat temperature of 1.
    private static final double CLASSIFICATION_TEMPERATURE = 0.0;

    @Autowired
    private VectorStore vectorStore;
    private final ChatClient chatClient;

    public DocumentClassificationServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String classify(DocumentDto document) {
        String categories = Arrays.stream(DocumentType.values())
                .filter(type -> type != DocumentType.UNKNOWN)
                .map(DocumentType::getLabel)
                .collect(Collectors.joining(", "));

        // Chunks are already embedded in the vector store from indexing, so
        // classification reuses that instead of re-fetching and re-parsing the
        // raw content from object storage.
        Filter.Expression documentFilter = new FilterExpressionBuilder()
                .eq(QueryConstants.PARENT_DOCUMENT_ID_METADATA_KEY, document.getId().toString())
                .build();

        log.info("Classifying document {}", document.getId());

        String response = chatClient.prompt()
                .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder().filterExpression(documentFilter).build())
                        .build())
                .options(OllamaChatOptions.builder().temperature(CLASSIFICATION_TEMPERATURE).build())
                .user(String.format(CLASSIFICATION_QUESTION, categories))
                .call()
                .content();

        DocumentType classification = DocumentType.fromLabel(response);

        log.info("Classified document {} as {}", document.getId(), classification.getLabel());

        return classification.getLabel();
    }
}
