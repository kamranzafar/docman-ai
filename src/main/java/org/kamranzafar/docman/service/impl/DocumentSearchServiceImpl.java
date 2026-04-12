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
import org.kamranzafar.docman.exception.DocmanException;
import org.kamranzafar.docman.exception.DocumentNotFoundException;
import org.kamranzafar.docman.model.QueryConstants;
import org.kamranzafar.docman.service.DocumentSearchService;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.FieldCollapse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.SourceConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DocumentSearchServiceImpl implements DocumentSearchService {
    @Value(value = "${spring.ai.vectorstore.opensearch.index-name}")
    private String indexName;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private OpenSearchClient openSearchClient;
    private final ChatClient chatClient;

    public DocumentSearchServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String vectorSearch(String question) {
        log.info("Vector search with prompt '{}'", question);

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

    @Override
    public List<Object> lexicalSearch(String query) {
        log.info("Searching for documents with query '{}'", query);

        SearchRequest request = SearchRequest.of(s -> s
                .index(indexName)
                .query(Query.of(q -> q.queryString(qs -> qs.query(query))))
                .collapse(FieldCollapse.of(fc -> fc.field(QueryConstants.QUERY_COLLAPSE_FIELD)))
                .source(SourceConfig.of(sc ->
                        sc.filter(sf -> sf.includes(QueryConstants.QUERY_SOURCE_INCLUDE))))
        );

        try {
            SearchResponse<Object> response = openSearchClient.search(request, Object.class);

            if (response.hits().hits().isEmpty()) {
                throw new DocumentNotFoundException("No matching document(s) found");
            }

            List<Object> documents = new ArrayList<>();
            for (Hit<Object> hit : response.hits().hits()) {
                log.info("Document found {}", hit.source());
                documents.add(hit.source());
            }

            return documents;
        } catch (IOException e) {
            throw new DocmanException("Failed to search documents", e);
        }
    }
}
