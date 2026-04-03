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

import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.spring.boot.autoconfigure.RestClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

@Configuration
public class OpenSearchConfig {

    @Value("${opensearch.host:localhost}")
    private String host;

    @Value("${opensearch.port:9200}")
    private int port;

    @Value("${opensearch.scheme:http}")
    private String scheme;

    @Value("${ssl.keystore.file}")
    private String keystoreFile;

    @Value("${ssl.keystore.password}")
    private String keystorePassword;

    @Bean
    public RestClientBuilderCustomizer customizer() {
        return new RestClientBuilderCustomizer() {
            @Override
            public void customize(HttpAsyncClientBuilder builder) {
                try {
                    final ClientTlsStrategyBuilder tlsStrategy = ClientTlsStrategyBuilder.create()
                            .setSslContext(SSLContextBuilder.create()
                                    .loadKeyMaterial(new File(keystoreFile),
                                            keystorePassword.toCharArray(), keystorePassword.toCharArray())
                                    .build());

                    final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder
                            .create()
                            .setTlsStrategy(tlsStrategy.buildAsync())
                            .build();

                    builder.setConnectionManager(connectionManager);
                } catch (final KeyManagementException | NoSuchAlgorithmException | KeyStoreException |
                               UnrecoverableKeyException | CertificateException | IOException ex) {
                    throw new RuntimeException("Failed to initialize SSL Context instance", ex);
                }
            }

            @Override
            public void customize(RestClientBuilder builder) {
                // No additional customizations needed
            }
        };
    }
}
