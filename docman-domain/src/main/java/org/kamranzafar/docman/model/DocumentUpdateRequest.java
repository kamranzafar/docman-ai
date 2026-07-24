/*
 *  Copyright 2026 Kamran Zafar
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.kamranzafar.docman.model;

import lombok.Data;

import java.util.Map;

@Data
public class DocumentUpdateRequest {
    Map<String, Object> metadata;
    String documentType;
    String updatedBy;
    // Only used by the presigned-upload update flow (POST /document/{id}), where the new
    // file's identity must be known upfront to generate the presigned URL - the multipart
    // update (PUT /document/{id}) derives these from the uploaded file part instead.
    String name;
    String contentType;
}
