package org.kamranzafar.docman.service;

import org.kamranzafar.docman.model.Document;

import java.util.UUID;

public interface DocumentService {
    Document create(Document document);
    Document update(Document document);
    Document delete(Document document);
    Document findMetadata(UUID id);
    Document findContent(UUID id);
}
