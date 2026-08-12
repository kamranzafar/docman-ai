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

package org.kamranzafar.docman.mcp;

import org.kamranzafar.docman.exception.DocmanException;
import org.kamranzafar.docman.model.DocumentDto;
import org.kamranzafar.docman.model.HybridSearchResult;
import org.kamranzafar.docman.model.QueryConstants;
import org.kamranzafar.docman.service.DocumentSearchService;
import org.kamranzafar.docman.service.DocumentService;
import org.kamranzafar.docman.service.ObjectStoreService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only MCP tool surface over the same services {@code DocumentController} uses. Deliberately
 * excludes create/update/delete/restore - those are higher-blast-radius for an agent to invoke
 * unsupervised and are left as REST-only operations for now.
 */
@Service
public class DocumentMcpTools {
    private final DocumentSearchService documentSearchService;
    private final DocumentService documentService;
    private final ObjectStoreService objectStoreService;

    public DocumentMcpTools(DocumentSearchService documentSearchService,
                             DocumentService documentService,
                             ObjectStoreService objectStoreService) {
        this.documentSearchService = documentSearchService;
        this.documentService = documentService;
        this.objectStoreService = objectStoreService;
    }

    @Tool(description = "Ask a natural-language question and get a RAG-generated answer grounded in the "
            + "indexed, non-deleted documents. Use this for open-ended questions about document content.")
    public String askQuestion(@ToolParam(description = "The question to answer") String question) {
        if (!StringUtils.hasText(question)) {
            throw new DocmanException("Question is mandatory");
        }
        if (question.length() > QueryConstants.QUERY_MAX_QUESTION_LENGTH) {
            throw new DocmanException(
                    "Question exceeds maximum length of " + QueryConstants.QUERY_MAX_QUESTION_LENGTH + " characters");
        }

        return documentSearchService.vectorSearch(question);
    }

    @Tool(description = "Search indexed document chunks by exact metadata field values (e.g. documentType, "
            + "createdBy). Use this when you know specific metadata to filter on rather than a free-text query.")
    public List<Object> searchByMetadata(
            @ToolParam(description = "Metadata field/value pairs to match, e.g. {\"documentType\": \"invoice\"}. "
                    + "At least one entry is required, up to " + QueryConstants.QUERY_MAX_FILTERS + ".")
            Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            throw new DocmanException("At least one metadata filter is mandatory");
        }
        if (filters.size() > QueryConstants.QUERY_MAX_FILTERS) {
            throw new DocmanException("Filters exceed maximum count of " + QueryConstants.QUERY_MAX_FILTERS);
        }

        return documentSearchService.lexicalSearch(filters);
    }

    @Tool(description = "Hybrid semantic + lexical search over indexed document chunks, fused via reciprocal "
            + "rank fusion. Use this for free-text queries where both meaning and exact terms matter, "
            + "optionally narrowed by metadata filters.")
    public List<HybridSearchResult> hybridSearch(
            @ToolParam(description = "The free-text search query") String query,
            @ToolParam(description = "Optional metadata field/value pairs to narrow the search, up to "
                    + QueryConstants.QUERY_MAX_FILTERS + ". May be null or omitted.", required = false)
            Map<String, Object> filters) {
        if (!StringUtils.hasText(query)) {
            throw new DocmanException("Query is mandatory");
        }
        if (filters != null && filters.size() > QueryConstants.QUERY_MAX_FILTERS) {
            throw new DocmanException("Filters exceed maximum count of " + QueryConstants.QUERY_MAX_FILTERS);
        }

        return documentSearchService.hybridSearch(query, filters);
    }

    @Tool(description = "Fetch a document's current metadata (name, status, documentType, custom metadata, "
            + "version, deleted flag) by its id.")
    public DocumentDto getDocumentMetadata(@ToolParam(description = "The document's UUID") UUID id) {
        return documentService.findMetadata(id);
    }

    @Tool(description = "List every recorded past version's metadata snapshot for a document, oldest first. "
            + "Content is never included, metadata only.")
    public List<DocumentDto> getDocumentRevisions(@ToolParam(description = "The document's UUID") UUID id) {
        return documentService.findRevisions(id);
    }

    @Tool(description = "Get a short-lived presigned URL to download a document's original file content.")
    public String getDocumentDownloadUrl(@ToolParam(description = "The document's UUID") UUID id) {
        DocumentDto document = documentService.findMetadata(id);
        return objectStoreService.presignedDownloadUrl(document);
    }
}
