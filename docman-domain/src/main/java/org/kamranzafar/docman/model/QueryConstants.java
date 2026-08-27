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
    String DELETED_METADATA_KEY = "deleted";
    String SUMMARY_METADATA_KEY = "summary";
    String QUERY_COLLAPSE_FIELD = "metadata." + PARENT_DOCUMENT_ID_METADATA_KEY + ".keyword";
    String QUERY_SOURCE_INCLUDE = "metadata";
    String QUERY_METADATA_FIELD_PREFIX = "metadata.";
    int QUERY_MAX_FILTERS = 10;
    // Caps the /ask question size - a free-text field with no other length limit that
    // otherwise goes straight into an LLM prompt.
    int QUERY_MAX_QUESTION_LENGTH = 2000;
    String CONTENT_FIELD = "content";

    // --- LLM consumption limits (OWASP LLM10: Unbounded Consumption) ---
    // Output-token ceilings passed to the chat model so a crafted prompt/context can't
    // drive an arbitrarily long (and costly) completion.
    int LLM_MAX_RESPONSE_TOKENS = 1024;
    // Classification only ever needs one short category label back.
    int CLASSIFICATION_MAX_RESPONSE_TOKENS = 16;
    // Caps the concatenated chunk text fed into the summarization prompt - a very large
    // document would otherwise produce a huge prompt regardless of the chunk count.
    int SUMMARY_MAX_INPUT_CHARS = 24_000;
    // Caps the summary text persisted back into document/chunk metadata, so an injection
    // in the source document can't stuff arbitrary bulk text into stored metadata.
    int SUMMARY_MAX_OUTPUT_CHARS = 2_000;
    // Caps the extracted document text that gets chunked and embedded at indexing time,
    // bounding embedding cost per upload.
    int INDEX_MAX_CONTENT_CHARS = 500_000;

    int HYBRID_SEARCH_TOP_K = 10;
    // Reciprocal Rank Fusion constant - dampens the influence of low-ranked hits from
    // either leg so one engine returning a huge, loosely-relevant result set can't drown
    // out a small, highly relevant result set from the other. 60 is the value used in the
    // original RRF paper (Cormack et al.) and is the de facto standard default.
    int HYBRID_SEARCH_RRF_RANK_CONSTANT = 60;
}
