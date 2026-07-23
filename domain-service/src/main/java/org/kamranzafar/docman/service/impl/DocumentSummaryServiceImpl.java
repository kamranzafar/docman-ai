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
import org.kamranzafar.docman.service.DocumentSummaryService;
import org.kamranzafar.docman.service.ObjectStoreService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class DocumentSummaryServiceImpl implements DocumentSummaryService {
    private static final String SUMMARY_PROMPT = """
            Summarize the following document in 2-3 concise sentences. \
            Respond with only the summary, no preamble.

            Document:
            %s""";

    @Autowired
    private ObjectStoreService objectStoreService;
    private final ChatClient chatClient;

    public DocumentSummaryServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String generateSummary(DocumentDto document) {
        InputStreamResource documentResource = objectStoreService.getDocumentContent(document);
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(documentResource);
        List<org.springframework.ai.document.Document> documents = tikaDocumentReader.get();

        if (documents.isEmpty() || documents.get(0).getText() == null) {
            log.info("No extractable text for document {}, skipping summary generation", document.getId());
            return null;
        }

        String text = documents.get(0).getText();

        log.info("Generating summary for document {}", document.getId());

        String summary = chatClient.prompt()
                .user(String.format(SUMMARY_PROMPT, text))
                .call()
                .content();

        log.info("Generated summary for document {}", document.getId());

        return summary;
    }
}
