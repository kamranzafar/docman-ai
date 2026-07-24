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
import org.kamranzafar.docman.mapper.DocumentMapper;
import org.kamranzafar.docman.model.DocumentDto;
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.model.QueryConstants;
import org.kamranzafar.docman.repository.DocumentMetadataRepository;
import org.kamranzafar.docman.service.DocumentIndexService;
import org.kamranzafar.docman.service.ObjectStoreService;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.UpdateByQueryResponse;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DocumentIndexServiceImpl implements DocumentIndexService {
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
    private DocumentMetadataRepository documentMetadataRepository;
    @Autowired
    private ObjectStoreService objectStoreService;
    @Autowired
    private TokenTextSplitter textSplitter;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private OpenSearchClient openSearchClient;
    @Autowired
    private DocumentMapper documentMapper;

    @Transactional
    @Override
    public void index(DocumentDto document) {
        InputStreamResource documentResource = objectStoreService.getDocumentContent(document);
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(documentResource);
        List<org.springframework.ai.document.Document> documents = tikaDocumentReader.get();

        if (!documents.isEmpty()) {
            org.springframework.ai.document.Document ragDoc = documents.get(0);

            assert ragDoc.getMedia() != null;
            assert ragDoc.getText() != null;

            Map<String, Object> indexedMetadata = document.getMetadata() != null
                    ? new HashMap<>(document.getMetadata()) : new HashMap<>();
            if (StringUtils.hasText(document.getDocumentType())) {
                indexedMetadata.put(QueryConstants.DOCUMENT_TYPE_METADATA_KEY, document.getDocumentType());
            }

            org.springframework.ai.document.Document d
                    = new org.springframework.ai.document.Document(
                    document.getId().toString(), ragDoc.getText(), indexedMetadata);

            List<org.springframework.ai.document.Document> splitDocuments = textSplitter.apply(List.of(d));

            vectorStore.add(splitDocuments);
            log.info("Added Documents to Vector Store {}", vectorStore.getName());

            // OpenSearch's default near-real-time refresh means newly-added chunks
            // aren't guaranteed searchable immediately after add() returns. Summary
            // generation and classification both query this document's chunks right
            // after indexing completes, so force a refresh here rather than let them
            // race the background refresh cycle and silently see zero results.
            refreshIndex();

            document.setStatus(DocumentStatus.INDEXED.name());

            documentMetadataRepository.save(documentMapper.toEntity(document));
        }
    }

    @Override
    public void updateMetadata(DocumentDto document) {
        Map<String, Object> mergeFields = document.getMetadata() != null
                ? new HashMap<>(document.getMetadata()) : new HashMap<>();
        if (StringUtils.hasText(document.getDocumentType())) {
            mergeFields.put(QueryConstants.DOCUMENT_TYPE_METADATA_KEY, document.getDocumentType());
        }

        if (mergeFields.isEmpty()) {
            log.debug("No metadata to sync into the vector store for document {}", document.getId());
            return;
        }

        Query filter = Query.of(q -> q.term(t -> t
                .field(QueryConstants.QUERY_METADATA_FIELD_PREFIX
                        + QueryConstants.PARENT_DOCUMENT_ID_METADATA_KEY + ".keyword")
                .value(FieldValue.of(document.getId().toString()))));

        try {
            UpdateByQueryResponse response = openSearchClient.updateByQuery(u -> u
                    .index(indexName)
                    .query(filter)
                    .script(s -> s.inline(i -> i
                            .lang("painless")
                            .source(METADATA_MERGE_SCRIPT)
                            .params("metadata", JsonData.of(mergeFields))))
                    .refresh(true));

            log.info("Synced metadata into {} indexed chunk(s) for document {}",
                    response.updated(), document.getId());
        } catch (IOException e) {
            throw new DocmanException("Failed to sync document metadata to vector index", e);
        }
    }

    @Override
    public void deleteIndex(DocumentDto document) {
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(QueryConstants.PARENT_DOCUMENT_ID_METADATA_KEY, document.getId().toString())
                .build();

        vectorStore.delete(filter);
        log.info("Deleted vector store entries for document {}", document.getId());
    }

    private void refreshIndex() {
        try {
            openSearchClient.indices().refresh(r -> r.index(indexName));
        } catch (IOException e) {
            throw new DocmanException("Failed to refresh vector index", e);
        }
    }
}
