/**
 *
 * Copyright 2026 Kamran Zafar
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kamranzafar.docman.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.model.DocumentProperties;
import org.kamranzafar.docman.model.DocumentStatus;
import org.kamranzafar.docman.repository.mongo.DocumentMetadataRepository;
import org.kamranzafar.docman.service.DocumentIndexService;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class DocumentIndexServiceImpl implements DocumentIndexService {
    @Autowired
    private DocumentMetadataRepository documentMetadataRepository;
    @Autowired
    private TokenTextSplitter textSplitter;
    @Autowired
    VectorStore vectorStore;

    @Transactional
    @Override
    public void index(Document document) {
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(new ByteArrayResource(document.getData()));
        List<org.springframework.ai.document.Document> documents = tikaDocumentReader.get();

        if (!documents.isEmpty()) {
            org.springframework.ai.document.Document ragDoc = documents.get(0);

            assert ragDoc.getMedia() != null;
            assert ragDoc.getText() != null;

            org.springframework.ai.document.Document d
                    = new org.springframework.ai.document.Document(
                    document.getId().toString(), ragDoc.getText(), document.getMetadata());

            List<org.springframework.ai.document.Document> splitDocuments = textSplitter.apply(List.of(d));

            vectorStore.add(splitDocuments);

            log.info("Added Documents to Vector Store {}", vectorStore.getName());

            document.getMetadata().put(DocumentProperties.STATUS, DocumentStatus.INDEXED.name());

            documentMetadataRepository.save(document);
        }
    }
}
