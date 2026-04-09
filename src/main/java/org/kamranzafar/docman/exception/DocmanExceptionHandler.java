package org.kamranzafar.docman.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class DocmanExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Object> handleServerErrors(RuntimeException ex, WebRequest request) {
        return super.handleExceptionInternal(ex, ex.getMessage(),
                new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @ExceptionHandler(DocmanException.class)
    public ResponseEntity<Object> handleDocmanErrors(RuntimeException ex, WebRequest request) {
        return super.handleExceptionInternal(ex, ex.getMessage(),
                new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<Object> handleDocumentNotFoundException(RuntimeException ex, WebRequest request) {
        return super.handleExceptionInternal(ex, ex.getMessage(),
                new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }
}
