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
import org.kamranzafar.docman.exception.DocumentNotFoundException;
import org.kamranzafar.docman.model.HybridSearchResult;
import org.kamranzafar.docman.model.QueryConstants;
import org.kamranzafar.docman.service.DocumentSearchService;
import org.kamranzafar.docman.service.DocumentVectorStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DocumentSearchServiceImpl implements DocumentSearchService {
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private DocumentVectorStore documentVectorStore;
    private final ChatClient chatClient;

    public DocumentSearchServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String vectorSearch(String question) {
        log.info("Vector search with prompt '{}'", question);

        // Base request carries only the deleted==false constraint - QuestionAnswerAdvisor
        // merges its own query text into a copy of this at call time (see
        // QuestionAnswerAdvisor#before), so the filter still applies per-question.
        SearchRequest notDeletedRequest = SearchRequest.builder()
                .filterExpression(notDeletedFilterExpression())
                .build();

        ChatResponse response = chatClient.prompt()
                .system(PromptGuardrails.SYSTEM_INSTRUCTIONS)
                .advisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(notDeletedRequest)
                        .promptTemplate(PromptGuardrails.QUESTION_ANSWER_TEMPLATE)
                        .build())
                .user(question)
                .call()
                .chatResponse();

        if (response != null) {
            return response.getResult().getOutput().getText();
        }

        return null;
    }

    @Override
    public List<Object> lexicalSearch(Map<String, Object> filters) {
        List<Object> documents = documentVectorStore.searchByMetadata(excludeDeleted(filters));

        if (documents.isEmpty()) {
            throw new DocumentNotFoundException("No matching document(s) found");
        }

        return documents;
    }

    @Override
    public List<HybridSearchResult> hybridSearch(String query, Map<String, Object> filters) {
        log.info("Hybrid search for '{}' with filters {}", query, filters);

        List<Document> semanticHits = semanticSearch(query, filters);
        List<Map<String, Object>> lexicalHits = documentVectorStore.searchByContent(
                query, excludeDeleted(filters), QueryConstants.HYBRID_SEARCH_TOP_K);

        // Reciprocal Rank Fusion: each leg contributes 1/(k+rank) per document (keyed by
        // parent_document_id, since both legs return chunk-level hits), regardless of the
        // leg's own score scale - vector cosine similarity and the lexical engine's own score
        // aren't otherwise comparable, so combining by rank rather than raw score is what
        // makes fusion meaningful.
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Map<String, Object>> metadataById = new LinkedHashMap<>();

        int rank = 1;
        for (Document doc : semanticHits) {
            accumulateRrf(parentDocumentId(doc.getMetadata()), doc.getMetadata(), rank++, rrfScores, metadataById);
        }

        rank = 1;
        for (Map<String, Object> metadata : lexicalHits) {
            accumulateRrf(parentDocumentId(metadata), metadata, rank++, rrfScores, metadataById);
        }

        List<HybridSearchResult> results = new ArrayList<>();
        rrfScores.forEach((id, score) -> results.add(HybridSearchResult.builder()
                .metadata(metadataById.get(id))
                .score(score)
                .build()));
        results.sort(Comparator.comparingDouble(HybridSearchResult::getScore).reversed());

        if (results.isEmpty()) {
            throw new DocumentNotFoundException("No matching document(s) found");
        }

        return results;
    }

    private void accumulateRrf(String id, Map<String, Object> metadata, int rank,
                                Map<String, Double> rrfScores, Map<String, Map<String, Object>> metadataById) {
        if (id == null) {
            return;
        }
        rrfScores.merge(id, 1.0 / (QueryConstants.HYBRID_SEARCH_RRF_RANK_CONSTANT + rank), Double::sum);
        metadataById.putIfAbsent(id, metadata);
    }

    private String parentDocumentId(Map<String, Object> metadata) {
        Object id = metadata == null ? null : metadata.get(QueryConstants.PARENT_DOCUMENT_ID_METADATA_KEY);
        return id == null ? null : String.valueOf(id);
    }

    private List<Document> semanticSearch(String query, Map<String, Object> filters) {
        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(query)
                .topK(QueryConstants.HYBRID_SEARCH_TOP_K)
                .filterExpression(toFilterExpression(filters));

        return vectorStore.similaritySearch(requestBuilder.build());
    }

    // Always ANDs in deleted==false, on top of whatever caller-supplied metadata filters
    // apply - a caller-supplied "deleted" key is dropped rather than honored, so the
    // exclusion can never be overridden from the request.
    private Filter.Expression toFilterExpression(Map<String, Object> filters) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op combined = b.eq(QueryConstants.DELETED_METADATA_KEY, false);

        if (filters != null) {
            for (Map.Entry<String, Object> entry : filters.entrySet()) {
                if (QueryConstants.DELETED_METADATA_KEY.equals(entry.getKey())) {
                    continue;
                }
                FilterExpressionBuilder.Op eq = b.eq(entry.getKey(), String.valueOf(entry.getValue()));
                combined = b.and(combined, eq);
            }
        }

        return combined.build();
    }

    private Filter.Expression notDeletedFilterExpression() {
        return new FilterExpressionBuilder().eq(QueryConstants.DELETED_METADATA_KEY, false).build();
    }

    // Forces deleted==false into the filter map used by DocumentVectorStore's raw
    // OpenSearch queries (searchByMetadata/searchByContent), overwriting any
    // caller-supplied "deleted" key so the exclusion can never be overridden.
    private Map<String, Object> excludeDeleted(Map<String, Object> filters) {
        Map<String, Object> merged = filters != null ? new HashMap<>(filters) : new HashMap<>();
        merged.put(QueryConstants.DELETED_METADATA_KEY, false);
        return merged;
    }
}
