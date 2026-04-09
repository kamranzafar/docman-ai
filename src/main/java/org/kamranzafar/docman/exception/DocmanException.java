package org.kamranzafar.docman.exception;

public class DocmanException extends RuntimeException {
    public DocmanException(String message) {
        super(message);
    }

    public DocmanException(String message, Throwable cause) {
        super(message, cause);
    }
}
