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

package org.kamranzafar.docman;

import io.minio.MinioClient;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class DocmanConfig {
    @Value(value = "${minio.address}")
    private String minioAddress;
    @Value(value = "${minio.user}")
    private String minioUser;
    @Value(value = "${minio.password}")
    private String minioPassword;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minioAddress)
                .credentials(minioUser, minioPassword)
                .build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }

    // Spring AI's model auto-configurations (OpenAI, Ollama) reuse whatever RestClient.Builder
    // bean is in context (falling back to a bare RestClient.builder() otherwise). With this
    // app's dependency set (Spring Boot 3.5.x + Micrometer observation-instrumented RestClient),
    // a gzip-encoded response from OpenAI's API gets truncated before Jackson can parse it,
    // failing every chat call with a JsonEOFException after the response's opening '{' -
    // reproducible with plain curl/JDK HttpClient calls to the same endpoint succeeding fine,
    // so it's specific to this app's RestClient wiring, not the OpenAI API itself. Forcing
    // identity encoding sidesteps it entirely.
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().defaultHeader("Accept-Encoding", "identity");
    }

    // Bounded and separate from Tomcat's request-handling pool, so slow chat-model
    // calls (which can take minutes on CPU-only local inference) don't exhaust it.
    @Bean
    public Executor askExecutor() {
        return Executors.newFixedThreadPool(10);
    }
}
