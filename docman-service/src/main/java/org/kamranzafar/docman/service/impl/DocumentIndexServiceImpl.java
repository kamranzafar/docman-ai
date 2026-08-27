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
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.model.QueryConstants;
import org.kamranzafar.docman.service.DocumentIndexService;
import org.kamranzafar.docman.service.DocumentVectorStore;
import org.kamranzafar.docman.service.ObjectStoreService;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DocumentIndexServiceImpl implements DocumentIndexService {
    @Autowired
    private ObjectStoreService objectStoreService;
    @Autowired
    private TokenTextSplitter textSplitter;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private DocumentVectorStore documentVectorStore;
    @Autowired
    private MongoTemplate mongoTemplate;

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

            // Cap the text that gets chunked and embedded (OWASP LLM10: Unbounded
            // Consumption) - multipart upload is already capped at 100MB, but a 100MB
            // text file would still produce an enormous number of embedding calls.
            String extractedText = ragDoc.getText();
            if (extractedText.length() > QueryConstants.INDEX_MAX_CONTENT_CHARS) {
                log.warn("Document {} extracted text is {} chars, truncating to {} before indexing",
                        document.getId(), extractedText.length(), QueryConstants.INDEX_MAX_CONTENT_CHARS);
                extractedText = extractedText.substring(0, QueryConstants.INDEX_MAX_CONTENT_CHARS);
            }

            Map<String, Object> indexedMetadata = document.getMetadata() != null
                    ? new HashMap<>(document.getMetadata()) : new HashMap<>();
            if (StringUtils.hasText(document.getDocumentType())) {
                indexedMetadata.put(QueryConstants.DOCUMENT_TYPE_METADATA_KEY, document.getDocumentType());
            }
            indexedMetadata.put(QueryConstants.DELETED_METADATA_KEY, document.isDeleted());

            org.springframework.ai.document.Document d
                    = new org.springframework.ai.document.Document(
                    document.getId().toString(), extractedText, indexedMetadata);

            List<org.springframework.ai.document.Document> splitDocuments = textSplitter.apply(List.of(d));

            vectorStore.add(splitDocuments);
            log.info("Added Documents to Vector Store {}", vectorStore.getName());

            // Deliberately not forcing a refresh here: at high ingestion rates that
            // would turn every single document's index() call into its own Lucene
            // segment flush instead of letting writes batch into the normal refresh
            // cycle. The workflow polls isIndexed() and waits for chunks to become
            // visible before running summary generation and classification - see
            // DocumentWorkflowImpl.waitForChunksIndexed.

            // Targeted field update, not a full document replace: `document` is a
            // workflow-level snapshot captured when the workflow started, so a full
            // save() here would clobber version/metadata/etc. if a user-driven
            // updateDocument() call changed them concurrently while indexing (Tika +
            // embedding) was still running - see DocumentServiceImpl.update for the
            // same fix applied to the other workflow-driven write path.
            mongoTemplate.updateFirst(
                    org.springframework.data.mongodb.core.query.Query.query(
                            Criteria.where("_id").is(document.getId())),
                    Update.update("status", DocumentStatus.INDEXED.name()),
                    org.kamranzafar.docman.model.Document.class);
        }
    }

    @Override
    public void updateMetadata(DocumentDto document) {
        Map<String, Object> mergeFields = document.getMetadata() != null
                ? new HashMap<>(document.getMetadata()) : new HashMap<>();
        if (StringUtils.hasText(document.getDocumentType())) {
            mergeFields.put(QueryConstants.DOCUMENT_TYPE_METADATA_KEY, document.getDocumentType());
        }
        // Always synced (unlike documentType, a boolean has no "absent" state worth
        // skipping) so every metadata-sync trigger - including softDelete - keeps each
        // chunk's deleted flag current.
        mergeFields.put(QueryConstants.DELETED_METADATA_KEY, document.isDeleted());

        documentVectorStore.mergeMetadata(document.getId().toString(), mergeFields);
    }

    @Override
    public void deleteIndex(DocumentDto document) {
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(QueryConstants.PARENT_DOCUMENT_ID_METADATA_KEY, document.getId().toString())
                .build();

        vectorStore.delete(filter);
        log.info("Deleted vector store entries for document {}", document.getId());
    }

    @Override
    public boolean isIndexed(DocumentDto document) {
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq(QueryConstants.PARENT_DOCUMENT_ID_METADATA_KEY, document.getId().toString())
                .build();

        // query is otherwise "" (SearchRequest's default) - the filterExpression above is
        // what actually scopes this to one document, but some embedding models (e.g.
        // OpenAI's, unlike Ollama's) reject an empty-string embedding input outright, so
        // this needs any non-empty text.
        return !vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(document.getName())
                        .filterExpression(filter)
                        .topK(1)
                        .build())
                .isEmpty();
    }
}
