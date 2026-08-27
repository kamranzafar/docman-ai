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

package org.kamranzafar.docman.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocmanExceptionHandlerTest {

    private final DocmanExceptionHandler handler = new DocmanExceptionHandler();
    private final ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());

    @Test
    void unhandledExceptionDoesNotLeakItsMessage() {
        RuntimeException infra = new RuntimeException(
                "S3 error: endpoint https://minio.internal:9000 rejected key AKIA... ");

        ResponseEntity<Object> response = handler.handleServerErrors(infra, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ProblemDetail body = assertInstanceOf(ProblemDetail.class, response.getBody());
        assertNotNull(body.getDetail());
        assertTrue(body.getDetail().startsWith("An unexpected error occurred. Reference: "),
                "detail should be the generic message, was: " + body.getDetail());
        assertTrue(body.getDetail().length() > "An unexpected error occurred. Reference: ".length(),
                "a reference id should be appended");
        assertTrue(!body.getDetail().contains("minio.internal") && !body.getDetail().contains("AKIA"),
                "infra detail must not appear in the response");
    }

    @Test
    void docmanExceptionMessageIsPreserved() {
        ResponseEntity<Object> response = handler.handleDocmanErrors(
                new DocmanException("Question is mandatory"), request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ProblemDetail body = assertInstanceOf(ProblemDetail.class, response.getBody());
        assertEquals("Question is mandatory", body.getDetail());
    }
}
