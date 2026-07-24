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
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A point-in-time snapshot of a {@link Document}'s metadata, recorded every time its
 * version is bumped (a new file upload or a user-driven metadata update), so past
 * versions remain retrievable after the live record moves on.
 */
@org.springframework.data.mongodb.core.mapping.Document(collection = "document_revisions")
@CompoundIndex(name = "documentId_version_idx", def = "{'documentId': 1, 'version': 1}", unique = true)
@Data
public class DocumentRevision {
    @Id
    String id;
    UUID documentId;
    int version;
    String name;
    String contentType;
    String documentType;
    Map<String, Object> metadata;
    Instant updatedAt;
    String updatedBy;
    boolean fileUpdated;
}
