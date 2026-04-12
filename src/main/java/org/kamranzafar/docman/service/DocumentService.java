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

import org.kamranzafar.docman.model.Document;

import java.util.List;
import java.util.UUID;

public interface DocumentService {
    Document create(Document document);

    Document update(Document document);

    Document delete(Document document);

    Document findMetadata(UUID id);

    String ask(String question);

    List<Object> search(String query);
}
