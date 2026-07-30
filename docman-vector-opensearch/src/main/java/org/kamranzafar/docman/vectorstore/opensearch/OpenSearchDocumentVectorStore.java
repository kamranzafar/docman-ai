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

package org.kamranzafar.docman.vectorstore.opensearch;

import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.exception.DocmanException;
import org.kamranzafar.docman.model.QueryConstants;
import org.kamranzafar.docman.service.DocumentVectorStore;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.UpdateByQueryResponse;
import org.opensearch.client.opensearch.core.search.FieldCollapse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.SourceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpenSearchDocumentVectorStore implements DocumentVectorStore {
    // Merges params.metadata into the existing metadata object key-by-key, rather than
    // replacing it outright, so chunk-only fields (parent_document_id, chunk_index,
    // total_chunks) added at indexing time survive a later metadata sync.
    private static final String METADATA_MERGE_SCRIPT = """
            if (ctx._source.metadata == null) { ctx._source.metadata = new HashMap(); }
            for (entry in params.metadata.entrySet()) {
                ctx._source.metadata[entry.getKey()] = entry.getValue();
            }
            """;

    @Value(value = "${spring.ai.vectorstore.opensearch.index-name}")
    private String indexName;
    @Autowired
    private OpenSearchClient openSearchClient;

    @Override
    public List<Object> searchByMetadata(Map<String, Object> filters) {
        log.info("Searching for documents with filters {}", filters);

        SearchRequest request = SearchRequest.of(s -> s
                .index(indexName)
                .query(Query.of(q -> q.bool(b -> {
                    filters.forEach((key, value) -> b.must(m -> m.match(mm -> mm
                            .field(QueryConstants.QUERY_METADATA_FIELD_PREFIX + key)
                            .query(FieldValue.of(String.valueOf(value))))));
                    return b;
                })))
                .collapse(FieldCollapse.of(fc -> fc.field(QueryConstants.QUERY_COLLAPSE_FIELD)))
                .source(SourceConfig.of(sc ->
                        sc.filter(sf -> sf.includes(QueryConstants.QUERY_SOURCE_INCLUDE))))
        );

        try {
            SearchResponse<Object> response = openSearchClient.search(request, Object.class);

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

    @Override
    public List<Map<String, Object>> searchByContent(String query, Map<String, Object> filters, int topK) {
        SearchRequest request = SearchRequest.of(s -> s
                .index(indexName)
                .size(topK)
                .query(Query.of(q -> q.bool(b -> {
                    b.must(m -> m.match(mm -> mm
                            .field(QueryConstants.CONTENT_FIELD)
                            .query(FieldValue.of(query))));
                    if (filters != null) {
                        filters.forEach((key, value) -> b.filter(f -> f.match(mm -> mm
                                .field(QueryConstants.QUERY_METADATA_FIELD_PREFIX + key)
                                .query(FieldValue.of(String.valueOf(value))))));
                    }
                    return b;
                })))
                .collapse(FieldCollapse.of(fc -> fc.field(QueryConstants.QUERY_COLLAPSE_FIELD)))
                .source(SourceConfig.of(sc ->
                        sc.filter(sf -> sf.includes(QueryConstants.QUERY_SOURCE_INCLUDE))))
        );

        try {
            SearchResponse<Object> response = openSearchClient.search(request, Object.class);

            List<Map<String, Object>> results = new ArrayList<>();
            for (Hit<Object> hit : response.hits().hits()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> source = (Map<String, Object>) hit.source();
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = source == null
                        ? null : (Map<String, Object>) source.get(QueryConstants.QUERY_SOURCE_INCLUDE);
                results.add(metadata);
            }

            return results;
        } catch (IOException e) {
            throw new DocmanException("Failed to search documents", e);
        }
    }

    @Override
    public void mergeMetadata(String parentDocumentId, Map<String, Object> metadata) {
        Query filter = Query.of(q -> q.term(t -> t
                .field(QueryConstants.QUERY_METADATA_FIELD_PREFIX
                        + QueryConstants.PARENT_DOCUMENT_ID_METADATA_KEY + ".keyword")
                .value(FieldValue.of(parentDocumentId))));

        try {
            // No forced refresh here: this runs off a Kafka consumer, nothing is synchronously
            // waiting on the result, so it's fine to let it become visible on the next natural
            // refresh cycle rather than force one per update at whatever rate updates arrive.
            UpdateByQueryResponse response = openSearchClient.updateByQuery(u -> u
                    .index(indexName)
                    .query(filter)
                    .script(s -> s.inline(i -> i
                            .lang("painless")
                            .source(METADATA_MERGE_SCRIPT)
                            .params("metadata", JsonData.of(metadata)))));

            log.info("Synced metadata into {} indexed chunk(s) for document {}",
                    response.updated(), parentDocumentId);
        } catch (IOException e) {
            throw new DocmanException("Failed to sync document metadata to vector index", e);
        }
    }
}
