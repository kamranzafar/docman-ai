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

package org.kamranzafar.docman.service;

import org.kamranzafar.docman.model.DocumentDto;
import org.kamranzafar.docman.model.DocumentRequest;
import org.kamranzafar.docman.model.DocumentUpdateRequest;

import java.util.UUID;

public interface DocumentService {
    DocumentDto create(DocumentRequest request);

    /**
     * Internal/system update (workflow status transitions, summary and classification
     * merges) - does not bump {@code version} or record a revision. User-driven updates
     * go through {@link #updateDocument}.
     */
    DocumentDto update(DocumentDto document);

    /**
     * User-driven metadata (and optionally file) update. Bumps {@code version}, records
     * a {@code DocumentRevision} snapshot, and replaces the metadata map outright.
     *
     * @param newFileName        the new file's name, or {@code null} for a metadata-only
     *                           update
     * @param newFileContentType the new file's content type, or {@code null} for a
     *                           metadata-only update
     */
    DocumentDto updateDocument(UUID id, DocumentUpdateRequest request, String newFileName, String newFileContentType);

    void delete(DocumentDto document);

    DocumentDto findMetadata(UUID id);

    /**
     * Looks up a specific past version's metadata snapshot from the revision history.
     */
    DocumentDto findMetadata(UUID id, int version);
}
