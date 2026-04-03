package org.kamranzafar.docman;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
