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
import org.kamranzafar.docman.mapper.DocumentMapper;
import org.kamranzafar.docman.model.DocumentDto;
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.model.QueryConstants;
import org.kamranzafar.docman.repository.DocumentMetadataRepository;
import org.kamranzafar.docman.service.DocumentIndexService;
import org.kamranzafar.docman.service.ObjectStoreService;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
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
    private DocumentMetadataRepository documentMetadataRepository;
    @Autowired
    private ObjectStoreService objectStoreService;
    @Autowired
    private TokenTextSplitter textSplitter;
    @Autowired
    private VectorStore vectorStore;
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
            document.setStatus(DocumentStatus.INDEXED.name());

            documentMetadataRepository.save(documentMapper.toEntity(document));
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
}
