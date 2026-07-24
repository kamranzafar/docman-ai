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
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * Indexing deliberately doesn't force a synchronous OpenSearch refresh after adding
 * chunks (that would turn every ingested document into its own Lucene segment flush
 * at high ingestion rates, instead of letting writes batch into the normal refresh
 * cycle). That means a document's chunks aren't guaranteed searchable the instant
 * indexing's activity returns, so callers that need to read a just-indexed document's
 * own chunks retry briefly here instead, giving the natural refresh interval a chance
 * to catch up.
 */
@Slf4j
final class VectorStoreConsistency {
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 300;

    private VectorStoreConsistency() {
    }

    static List<Document> awaitChunks(VectorStore vectorStore, SearchRequest searchRequest) {
        List<Document> chunks = List.of();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            chunks = vectorStore.similaritySearch(searchRequest);
            if (!chunks.isEmpty()) {
                if (attempt > 1) {
                    log.debug("Vector store chunks became visible after {} attempt(s)", attempt);
                }
                return chunks;
            }
            if (attempt == MAX_ATTEMPTS) {
                log.debug("Vector store chunks still not visible after {} attempts", MAX_ATTEMPTS);
                return chunks;
            }
            sleep();
        }

        return chunks;
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
