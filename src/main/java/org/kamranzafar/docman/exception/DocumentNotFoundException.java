package org.kamranzafar.docman.exception;

public class DocumentNotFoundException extends DocmanException {
    public DocumentNotFoundException(String message) {
        super(message);
    }

    public DocumentNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
