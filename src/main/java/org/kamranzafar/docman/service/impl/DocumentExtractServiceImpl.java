package org.kamranzafar.docman.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.service.DocumentExtractService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.util.Base64;

@Slf4j
@Service
public class DocumentExtractServiceImpl implements DocumentExtractService {
    private final ChatClient chatClient;

    public DocumentExtractServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String extract(Document document) {
        log.info("Extracting document with id {}", document.getId());

        try {
            String content = extractFromAi(document.getData(), MimeTypeUtils.parseMimeType(document.getContentType()));
            log.info("Extracted document content with id '{}': {}", document.getId(), content);

            return content;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String extractFromAi(byte[] data, MimeType mimeType) throws IOException {
        String userAsking = "Please look at this file and extract all the text content.";

        return chatClient.prompt()
                .system("Look at the base64 content with mime type " + mimeType.toString() + ". Extract all the text content." +
                        "Provide the output as plain text, maintaining the original layout and line breaks where appropriate. " +
                        "Include all visible text from any included images in the file. " +
                        "Output only the extracted text without commentary. The file content is " +
                        Base64.getEncoder().encodeToString(data)).user(
                        userMessage -> userMessage.text(userAsking))
                .call().content();
    }
}
