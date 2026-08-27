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

package org.kamranzafar.docman.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    private final RateLimitFilter filter =
            new RateLimitFilter(true, 2, 2, Duration.ofMinutes(1));

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRemoteAddr("10.0.0.1");
        return req;
    }

    @Test
    void allowsUpToCapacityThenReturns429() throws Exception {
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (req, res) -> passed.incrementAndGet();

        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request("/api/v1/document/ask"), response, chain);
            assertEquals(200, response.getStatus());
        }
        assertEquals(2, passed.get());

        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilterInternal(request("/api/v1/document/ask"), limited, chain);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), limited.getStatus());
        assertEquals(2, passed.get(), "chain must not run once the bucket is empty");
        assertNotNull(limited.getHeader(HttpHeaders.RETRY_AFTER));
        assertTrue(Long.parseLong(limited.getHeader(HttpHeaders.RETRY_AFTER)) >= 1);
        assertTrue(limited.getContentAsString().contains("Rate limit exceeded"));
    }

    @Test
    void separateIpsGetSeparateBuckets() throws Exception {
        FilterChain chain = (req, res) -> { };

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockHttpServletRequest req = request("/api/v1/document/search");
            req.setRemoteAddr("10.0.0." + i);
            filter.doFilterInternal(req, response, chain);
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void nonCostPathsAreNotFiltered() {
        assertTrue(filter.shouldNotFilter(request("/api/v1/document/metadata/abc")));
        assertFalse(filter.shouldNotFilter(request("/api/v1/document/ask")));
        assertFalse(filter.shouldNotFilter(request("/api/v1/document/search/hybrid")));
        assertFalse(filter.shouldNotFilter(request("/mcp")));
    }

    @Test
    void disabledFilterSkipsEverything() {
        RateLimitFilter disabled = new RateLimitFilter(false, 1, 1, Duration.ofMinutes(1));
        assertTrue(disabled.shouldNotFilter(request("/api/v1/document/ask")));
    }
}
