package org.kamranzafar.docman.service;

import org.kamranzafar.docman.model.Document;

public interface DocumentIndexService {
    Document index(String documentId, String content);
}
