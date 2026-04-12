/**
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

package org.kamranzafar.docman.service.impl;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.kamranzafar.docman.exception.DocmanException;
import org.kamranzafar.docman.model.Document;
import org.kamranzafar.docman.service.PresignedUrlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class PresignedUrlServiceImpl implements PresignedUrlService {
    public static final String MINIO_RESPONSE_CONTENT_TYPE_KEY = "response-content-type";
    @Autowired
    private MinioClient minioClient;

    @Value(value = "${minio.bucket}")
    private String minioBucket;

    @Value(value = "${minio.presigned.upload-url-expiry}")
    private int minioUploadExpiry;

    @Value(value = "${minio.presigned.download-url-expiry}")
    private int minioDownloadExpiry;

    @Override
    public String downloadUrl(Document document) {
        Map<String, String> reqParams = new HashMap<>();
        reqParams.put(MINIO_RESPONSE_CONTENT_TYPE_KEY, document.getContentType());

        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(minioBucket)
                            .object(String.format("%s/%s", document.getId(), document.getName()))
                            .expiry(minioDownloadExpiry)
                            .extraQueryParams(reqParams)
                            .build());
        } catch (Throwable e) {
            throw new DocmanException(e.getMessage(), e);
        }
    }

    @Override
    public String uploadUrl(Document document) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(minioBucket)
                            .object(String.format("%s/%s", document.getId(), document.getName()))
                            .expiry(minioUploadExpiry)
                            .build());
        } catch (Throwable e) {
            throw new DocmanException(e.getMessage(), e);
        }
    }
}
