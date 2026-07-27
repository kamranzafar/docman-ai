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
import org.kamranzafar.docman.model.QueryConstants;
import org.kamranzafar.docman.service.DocumentSummaryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentSummaryServiceImpl implements DocumentSummaryService {
    private static final String SUMMARY_PROMPT = """
            Summarize the following document in 2-3 concise sentences. \
            Respond with only the summary, no preamble.

            Document:
            %s""";

    // Large enough to pull every chunk belonging to a single document
    // regardless of similarity ranking - the parent_document_id filter below
    // already scopes candidates to just this document, so this is only an
    // upper bound, not a real cap in practice.
    private static final int MAX_CHUNKS = 1000;

    @Autowired
    private VectorStore vectorStore;
    private final ChatClient chatClient;

    public DocumentSummaryServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String generateSummary(DocumentDto document) {
        // Chunks are already embedded in the vector store from indexing, so
        // summarization reuses that instead of re-fetching and re-parsing the
        // raw content from object storage.
        Filter.Expression documentFilter = new FilterExpressionBuilder()
                .eq(QueryConstants.PARENT_DOCUMENT_ID_METADATA_KEY, document.getId().toString())
                .build();

        // The workflow waits for this document's chunks to become visible in the
        // vector store before invoking this activity (see DocumentWorkflowImpl),
        // so no retry is needed here - a still-empty result means the wait
        // window elapsed, not that this call raced the indexing write.
        List<org.springframework.ai.document.Document> chunks = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(document.getName())
                        .filterExpression(documentFilter)
                        .topK(MAX_CHUNKS)
                        .build());

        if (chunks.isEmpty()) {
            log.info("No indexed chunks for document {}, skipping summary generation", document.getId());
            return null;
        }

        // Chunks come back in similarity order, not document order - restore
        // reading order before concatenating so the summary reflects the
        // document's actual structure.
        String text = chunks.stream()
                .sorted(Comparator.comparingInt(chunk ->
                        ((Number) chunk.getMetadata().getOrDefault("chunk_index", 0)).intValue()))
                .map(org.springframework.ai.document.Document::getText)
                .collect(Collectors.joining("\n"));

        log.info("Generating summary for document {}", document.getId());

        String summary = chatClient.prompt()
                .user(String.format(SUMMARY_PROMPT, text))
                .call()
                .content();

        log.info("Generated summary for document {}", document.getId());

        return summary;
    }
}
