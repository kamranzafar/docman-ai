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

public interface QueryConstants {
    String PARENT_DOCUMENT_ID_METADATA_KEY = "parent_document_id";
    String DOCUMENT_TYPE_METADATA_KEY = "documentType";
    String SUMMARY_METADATA_KEY = "summary";
    String QUERY_COLLAPSE_FIELD = "metadata." + PARENT_DOCUMENT_ID_METADATA_KEY + ".keyword";
    String QUERY_SOURCE_INCLUDE = "metadata";
    String QUERY_METADATA_FIELD_PREFIX = "metadata.";
    int QUERY_MAX_FILTERS = 10;
}
