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

package org.kamranzafar.docman.service;

import java.util.List;
import java.util.Map;

/**
 * The subset of vector-store operations that have no equivalent in Spring AI's portable
 * {@code VectorStore} API (semantic search, add, delete, and the RAG advisor all go through that
 * directly - see {@link DocumentSearchService}/{@link DocumentIndexService}). Implementations are
 * necessarily provider-specific; the OpenSearch one lives in the docman-vector-opensearch module.
 */
public interface DocumentVectorStore {
    /**
     * Structured search matching each filter entry against the chunk's {@code metadata} subtree.
     * Returns raw hit sources (each typically a {@code Map} with a single {@code "metadata"} key);
     * an empty list means no matches - callers decide whether that's an error.
     */
    List<Object> searchByMetadata(Map<String, Object> filters);

    /**
     * Free-text search over chunk content, optionally narrowed by the same filters as
     * {@link #searchByMetadata}. Returns just the chunk metadata maps, already in relevance-rank
     * order (best match first) - callers that need fusion (e.g. Reciprocal Rank Fusion) only need
     * the rank, not the underlying engine's raw score.
     */
    List<Map<String, Object>> searchByContent(String query, Map<String, Object> filters, int topK);

    /**
     * Merges the given key/value pairs into the existing metadata of every indexed chunk belonging
     * to the given parent document, without touching embeddings or content.
     */
    void mergeMetadata(String parentDocumentId, Map<String, Object> metadata);
}
