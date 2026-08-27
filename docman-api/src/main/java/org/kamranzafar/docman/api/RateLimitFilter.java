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

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-client-IP token-bucket rate limit on the cost-bearing endpoints - the ones that
 * trigger an LLM call or a vector-store query (OWASP LLM10: Unbounded Consumption). Every
 * other path (metadata reads, uploads, deletes) is left untouched. Buckets are held in
 * process memory; this is a single-node guard, not a distributed quota.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    // Prefixes (post servlet-context) that cost real compute per request.
    private static final List<String> RATE_LIMITED_PATHS = List.of(
            "/api/v1/document/ask",
            "/api/v1/document/search",
            "/mcp");

    private final boolean enabled;
    private final long capacity;
    private final long refillTokens;
    private final Duration refillPeriod;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${docman.ratelimit.enabled:true}") boolean enabled,
            @Value("${docman.ratelimit.capacity:20}") long capacity,
            @Value("${docman.ratelimit.refill-tokens:20}") long refillTokens,
            @Value("${docman.ratelimit.refill-period:1m}") Duration refillPeriod) {
        this.enabled = enabled;
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriod = refillPeriod;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = request.getRequestURI();
        return RATE_LIMITED_PATHS.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Bucket bucket = buckets.computeIfAbsent(clientKey(request), k -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1,
                TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        log.warn("Rate limit exceeded for {} on {}", clientKey(request), request.getRequestURI());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.getWriter().write(String.format(
                "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                        + "\"detail\":\"Rate limit exceeded, retry after %d seconds\"}",
                retryAfterSeconds));
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillTokens, refillPeriod)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    // First X-Forwarded-For hop when present (the app sits behind a proxy in most
    // deployments), else the socket peer.
    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
