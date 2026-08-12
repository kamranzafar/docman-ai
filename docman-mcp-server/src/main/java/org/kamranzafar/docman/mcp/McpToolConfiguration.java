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

import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Registers {@link DocumentMcpTools}' tools directly as {@code SyncToolSpecification}
 * beans rather than as a generic {@code ToolCallbackProvider}/{@code ToolCallback} bean.
 * The generic form is also swept up by the app's own internal chat-model tool-calling
 * resolver ({@code ToolCallingAutoConfiguration}), and since these tools call back into
 * {@code DocumentSearchService} - the same service the internal RAG {@code ChatClient}
 * depends on - that creates a circular bean dependency at startup. Going straight to the
 * MCP-specific bean type sidesteps that resolver entirely.
 */
@Configuration
public class McpToolConfiguration {
    @Bean
    public List<McpServerFeatures.SyncToolSpecification> documentToolSpecifications(
            DocumentMcpTools documentMcpTools) {
        return McpToolUtils.toSyncToolSpecifications(
                MethodToolCallbackProvider.builder().toolObjects(documentMcpTools).build().getToolCallbacks());
    }
}
