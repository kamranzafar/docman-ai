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

import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.opensearch.OpenSearchVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
public class OpenSearchConfig {//extends AbstractOpenSearchConfiguration {
    @Value("${spring.ai.vectorstore.opensearch.uris}")
    private String uris;

    @Value("${spring.ai.vectorstore.opensearch.username}")
    private String username;

    @Value("${spring.ai.vectorstore.opensearch.password}")
    private String password;

    @Value("${spring.ai.vectorstore.opensearch.index-name}")
    private String indexName;

    @Value("classpath:docman-mapping.json")
    private Resource resource;

    @Autowired
    private EmbeddingModel embeddingModel;

//    @Bean
//    public VectorStore vectorStore(OpenSearchClient openSearchClient, EmbeddingModel embeddingModel) {
//        return OpenSearchVectorStore.builder(openSearchClient, embeddingModel)
//                .index(indexName)                // Optional: defaults to "spring-ai-document-index"
//                .similarityFunction("l2")             // Optional: defaults to "cosinesimil"
//                .useApproximateKnn(true)              // Optional: defaults to false (exact k-NN)
//                .dimensions(1536)                     // Optional: defaults to 1536 or embedding model's dimensions
//                .initializeSchema(true)               // Optional: defaults to false
//                .batchingStrategy(new TokenCountBatchingStrategy()) // Optional: defaults to TokenCountBatchingStrategy
//                .build();
//    }

//    @Override
//    @Bean
//    public RestHighLevelClient opensearchClient() {
//        return new RestHighLevelClient(RestClient.builder(HttpHost.create(uris))
//                .setDefaultHeaders(new Header[]{
//                        new BasicHeader("Authorization",
//                                String.format("Basic %s",
//                                        Base64.getEncoder().encodeToString((username + ":" + password).getBytes())))
//                }));
//
//    }

//    @Bean
//    public OpenSearchClient openSearchClient() {
//        RestClient restClient = RestClient.builder(HttpHost.create(uris))
//                .setDefaultHeaders(new Header[]{
//                        new BasicHeader("Authorization",
//                                String.format("Basic %s",
//                                        Base64.getEncoder().encodeToString((username + ":" + password).getBytes())))
//                }).build();
//
//        return new OpenSearchClient(new RestClientTransport(
//                restClient, new JacksonJsonpMapper()));
//    }

}
