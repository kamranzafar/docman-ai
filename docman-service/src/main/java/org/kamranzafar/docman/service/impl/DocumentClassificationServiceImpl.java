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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentClassificationServiceImpl implements DocumentClassificationService {
    private static final String CLASSIFICATION_QUESTION = """
            What type of document is this? Choose exactly one category from the list below, using \
            the description of each to pick the best match:
            %s
            If the document does not clearly match any of these categories, respond with "unknown".
            Respond with only the category name (e.g. "invoices"), no preamble, explanation, or punctuation.""";

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
                .map(type -> "- " + type.getLabel() + ": " + type.getDescription())
                .collect(Collectors.joining("\n"));

        // Chunks are already embedded in the vector store from indexing, so
        // classification reuses that instead of re-fetching and re-parsing the
        // raw content from object storage.
        Filter.Expression documentFilter = new FilterExpressionBuilder()
                .eq(QueryConstants.PARENT_DOCUMENT_ID_METADATA_KEY, document.getId().toString())
                .build();

        // Cheap existence check before the expensive LLM call. The workflow already
        // waits for this document's chunks to become visible in the vector store
        // before invoking this activity (see DocumentWorkflowImpl), so no retry is
        // needed here - a still-empty result means the wait window elapsed, not
        // that this call raced the indexing write.
        //
        // query is set to the document's own name rather than left as SearchRequest's
        // default "" - the filterExpression is what actually scopes this to one
        // document, but some embedding models (e.g. OpenAI's, unlike Ollama's) reject
        // an empty-string embedding input outright.
        List<org.springframework.ai.document.Document> existing = vectorStore.similaritySearch(
                SearchRequest.builder().query(document.getName()).filterExpression(documentFilter).topK(1).build());

        if (existing.isEmpty()) {
            log.info("No indexed chunks for document {}, defaulting classification to unknown", document.getId());
            return DocumentType.UNKNOWN.getLabel();
        }

        log.info("Classifying document {}", document.getId());

        String response = chatClient.prompt()
                .system(PromptGuardrails.SYSTEM_INSTRUCTIONS)
                .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder().filterExpression(documentFilter).build())
                        .promptTemplate(PromptGuardrails.QUESTION_ANSWER_TEMPLATE)
                        .build())
                .options(ChatOptions.builder().temperature(CLASSIFICATION_TEMPERATURE).build())
                .user(String.format(CLASSIFICATION_QUESTION, categories))
                .call()
                .content();

        DocumentType classification = DocumentType.fromLabel(response);

        log.info("Classified document {} as {}", document.getId(), classification.getLabel());

        return classification.getLabel();
    }
}
