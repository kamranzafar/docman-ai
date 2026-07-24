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

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.kamranzafar.docman.exception.DocmanException;
import org.kamranzafar.docman.model.DocumentDto;
import org.kamranzafar.docman.service.ObjectStoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ObjectStoreServiceImpl implements ObjectStoreService {
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
    public boolean documentExists(DocumentDto document) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(objectKey(document)).build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Throwable e) {
            throw new DocmanException(e.getMessage(), e);
        }
    }

    @Override
    public void saveDocumentContent(DocumentDto document, InputStream content, long size) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(objectKey(document))
                    .contentType(document.getContentType())
                    .stream(content, size, -1)
                    .build());
        } catch (Throwable e) {
            throw new DocmanException(e.getMessage(), e);
        }
    }

    @Override
    public void deleteDocumentContent(DocumentDto document) {
        // Deletes every version's object, not just the current one, since a document
        // delete tears down the whole document rather than a single version.
        try {
            String prefix = document.getId() + "/";
            List<DeleteObject> objects = new ArrayList<>();
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(minioBucket)
                    .prefix(prefix)
                    .recursive(true)
                    .build())) {
                objects.add(new DeleteObject(result.get().objectName()));
            }

            if (objects.isEmpty()) {
                return;
            }

            for (Result<DeleteError> result : minioClient.removeObjects(RemoveObjectsArgs.builder()
                    .bucket(minioBucket)
                    .objects(objects)
                    .build())) {
                DeleteError error = result.get();
                log.warn("Failed to delete object {}: {}", error.objectName(), error.message());
            }
        } catch (Throwable e) {
            throw new DocmanException(e.getMessage(), e);
        }
    }

    @Override
    public InputStreamResource getDocumentContent(DocumentDto document) {
        try {
            return new InputStreamResource(
                    minioClient.getObject(GetObjectArgs.builder()
                            .bucket(minioBucket)
                            .object(objectKey(document))
                            .build()));
        } catch (Throwable e) {
            throw new DocmanException(e.getMessage(), e);
        }
    }

    @Override
    public String presignedDownloadUrl(DocumentDto document) {
        Map<String, String> reqParams = new HashMap<>();
        reqParams.put(MINIO_RESPONSE_CONTENT_TYPE_KEY, document.getContentType());

        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(minioBucket)
                            .object(objectKey(document))
                            .expiry(minioDownloadExpiry)
                            .extraQueryParams(reqParams)
                            .build());
        } catch (Throwable e) {
            throw new DocmanException(e.getMessage(), e);
        }
    }

    @Override
    public String presignedUploadUrl(DocumentDto document) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(minioBucket)
                            .object(objectKey(document))
                            .expiry(minioUploadExpiry)
                            .build());
        } catch (Throwable e) {
            throw new DocmanException(e.getMessage(), e);
        }
    }

    private String objectKey(DocumentDto document) {
        return String.format("%s/%d/%s", document.getId(), document.getVersion(), document.getName());
    }
}
