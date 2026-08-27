# Setup & Deployment

## Prerequisites

- **Java 21**
- **Maven** 3.9+ (or use the included `.mvn/` wrapper configuration)
- **Docker** and **Docker Compose**, for MinIO, MongoDB, OpenSearch, Temporal, and Kafka
- **Ollama**, running natively on the host (not containerized in this setup)

## 1. Install Ollama and pull the required models

```shell
brew install ollama
```

Docman uses two models:

```shell
ollama pull nomic-embed-text   # embeddings
ollama pull llama3.1           # chat, RAG answers, and document summarization
```

Verify Ollama is reachable and both models are present:

```shell
curl -s http://localhost:11434/api/tags
```

Ollama inference in this setup runs on CPU unless you have GPU acceleration configured for it
separately; `llama3.1` chat calls (RAG answers, summaries) can take anywhere from tens of seconds
to several minutes depending on your hardware. See
[Configuration reference](#configuration-reference) for the relevant timeouts.

## 2. Start backing services

```shell
docker-compose -f docker/docker-compose.yml up -d
```

This brings up:

| Service                     | Container                    | Host port(s)      |
|-------------------------------|---------------------------------|----------------------|
| MongoDB                        | `docman-db`                     | 27017                |
| MinIO                          | `docman-minio`                  | 9000 (API), 9001 (console) |
| OpenSearch                     | `docman-opensearch-node1`        | 9200, 9600           |
| OpenSearch Dashboards            | `docman-opensearch-dashboards`   | 5601                 |
| Temporal server                  | `docman-temporal`                | 7233 (gRPC), 8088 (internal) |
| Temporal Web UI                    | `docman-temporal-ui`             | 8080                 |
| Temporal admin tools                | `docman-temporal-admin-tools`    | —                     |
| Kafka                                | `docman-kafka`                  | 9092                  |
| Zookeeper                             | `docman-zookeeper`               | 22181                 |
| Temporal's Postgres backing DB          | `docman-temporal-db`              | 5432                   |

The `temporalio/auto-setup` image automatically creates the `default` Temporal namespace and
database schema on first start — no manual namespace setup is required. (`scripts/create-namespace.sh`
and `scripts/setup-postgres.sh` are the scripts that image runs internally; you shouldn't need to
run them yourself.)

MinIO's bucket (`docman`, configurable via `minio.bucket`) and the OpenSearch vector index
(`docman-vector-index`) are both created automatically by the application on first use —
`spring.ai.vectorstore.opensearch.initialize-schema: true` creates the index with the mapping in
`docman-api/src/main/resources/docman-mapping.json` if it doesn't already exist.

Wait for OpenSearch to report healthy before starting the app:

```shell
curl -sk -u admin:SecureP@ssword1 https://localhost:9200/_cluster/health
```

## 3. Build the project

This is a 7-module Maven reactor (`docman-domain`, `docman-persistence`, `docman-service`,
`docman-vector-opensearch`, `docman-workflow`, `docman-mcp-server`, `docman-api`). Build the whole
thing from the repository root:

```shell
mvn clean package
```

This produces an executable Spring Boot fat jar at `docman-api/target/docman-api-*.jar`
(`Start-Class: org.kamranzafar.docman.Application`) — every other module's classes are pulled in
transitively.

To build/compile just one module and its dependencies, use `-pl <module> -am`:

```shell
mvn -pl docman-api -am compile
```

## 4. Run the application

Via Maven (the runnable module is `docman-api`):

```shell
mvn -pl docman-api -am spring-boot:run
```

Or run the built jar directly:

```shell
java -jar docman-api/target/docman-api-1.0-SNAPSHOT.jar
```

No manual TLS setup is needed for OpenSearch: its Docker image ships with a fixed, well-known
self-signed demo certificate (the same across every pull of the image, not regenerated per
container), signed by a root CA whose public certificate is checked into this repo
(`docman-api/src/main/resources/opensearch-root-ca.pem`) and wired in as a Spring Boot SSL bundle
(`spring.ssl.bundle.pem.opensearch`, referenced by `spring.ai.vectorstore.opensearch.ssl-bundle` in
`application.yaml`). This is scoped to just the OpenSearch connection — it doesn't touch the JVM's
default trust store — and works out of the box against the bundled `docker-compose.yml` setup with
no environment variables or `keytool` steps required.

If you point this app at a *different* OpenSearch instance with its own (non-demo) certificate,
replace `opensearch-root-ca.pem` with that instance's CA certificate, or remove the `ssl-bundle`
property to fall back to the JVM's default trust store (and configure that trust store yourself).

Verify the app is up:

```shell
curl -s http://localhost:8081/actuator/health
```

## Configuration reference

All configuration lives in `docman-api/src/main/resources/application.yaml`. Key properties:

| Property                                        | Default                        | Notes |
|---------------------------------------------------|-----------------------------------|-------|
| `server.port`                                        | `8088`                            | **Clashes with the Temporal container's internal port 8088** (see [Troubleshooting](#troubleshooting)) — override with `--server.port=<port>` if you hit this |
| `minio.address`                                        | `http://localhost:9000`           | MinIO endpoint |
| `minio.bucket`                                           | `docman`                          | Bucket auto-created on first use |
| `minio.presigned.upload-url-expiry`                        | `3600` (seconds)                  | Presigned upload URL TTL |
| `minio.presigned.download-url-expiry`                        | `300` (seconds)                   | Presigned download URL TTL |
| `spring.data.mongodb.host` / `.port`                            | `localhost` / `27017`             | MongoDB connection |
| `spring.temporal.connection.target`                               | `localhost:7233`                  | Temporal server gRPC endpoint |
| `spring.ai.vectorstore.opensearch.uris`                             | `https://localhost:9200`          | OpenSearch endpoint |
| `spring.ai.vectorstore.opensearch.dimensions`                        | `768`                             | Must match the active embedding model's output size (`nomic-embed-text` = 768; `text-embedding-3-small` = 1536 in the `prod` profile) |
| `spring.ai.model.chat` / `spring.ai.model.embedding`                    | `ollama` / `ollama`               | Selects which bundled Spring AI starter (Ollama or OpenAI) actually wires up the `ChatModel`/`EmbeddingModel` beans — see [Production profile (OpenAI)](#production-profile-openai) |
| `spring.ai.model.audio.speech` / `.audio.transcription` / `.image` / `.moderation` | `none` / `none` / `none` / `none` | Explicitly disabled — this app never uses these capabilities, but the OpenAI starter's autoconfigurations for them default to *enabled* when unset (unlike chat/embedding), so leaving them out would try to build unconfigured OpenAI beans on startup and fail. See [Troubleshooting](#troubleshooting) |
| `spring.ai.ollama.base-url`                                            | `http://localhost:11434/`         | Ollama endpoint |
| `spring.ai.ollama.embedding.options.model`                               | `nomic-embed-text`                | Embedding model |
| `spring.ai.ollama.chat.model`                                               | `llama3.1`                        | Chat model, used for RAG answers, summarization, and classification |
| `spring.ai.ollama.chat.options.timeout`                                       | `600s`                            | Max time for a single Ollama chat call |
| `spring.ai.ollama.chat.options.temperature`                                     | `1`                               | Default sampling temperature for RAG answers and summaries. Document classification overrides this to `0` per-call in code regardless, via a portable `ChatOptions` override that works with any provider (see [`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-summarization--classification)) |
| `spring.servlet.multipart.max-file-size` / `.max-request-size`                  | `100MB`                           | Direct (`PUT /api/v1/document`) upload size cap |
| `kafka.address`                                                                    | `localhost:9092`                  | Kafka bootstrap server |
| `kafka.topic`                                                                        | `documents`                       | Lifecycle notification topic |
| `kafka.metadata-sync-topic`                                                            | `document-metadata-sync`          | Trigger topic for syncing vector store chunk metadata on every `DocumentService.update()` (see [`docs/ARCHITECTURE.md`](ARCHITECTURE.md#keeping-vector-store-metadata-in-sync)) |
| `spring.ai.mcp.server.name` / `.version`                                                  | `docman-mcp-server` / `1.0.0`      | Identifies the MCP server to connecting clients |
| `spring.ai.mcp.server.protocol`                                                              | `STREAMABLE`                      | Streamable-HTTP transport, served at the default `/mcp` endpoint on the same port as the REST API (see [`docs/ARCHITECTURE.md`](ARCHITECTURE.md#mcp-server)) |
| `docman.ratelimit.enabled`                                                                     | `true`                            | Per-client-IP token-bucket rate limit on `/ask`, `/search`, `/search/hybrid`, and `/mcp` (OWASP LLM10 — see [`docs/AI-SECURITY.md`](AI-SECURITY.md)) |
| `docman.ratelimit.capacity` / `.refill-tokens` / `.refill-period`                              | `20` / `20` / `1m`                | Bucket size and greedy refill rate per IP; over-limit requests get `429` + `Retry-After` |

## MCP Server (agent access)

`docman-mcp-server` exposes six read-only tools (`askQuestion`, `searchByMetadata`, `hybridSearch`,
`getDocumentMetadata`, `getDocumentRevisions`, `getDocumentDownloadUrl`) to MCP clients at
`http://localhost:8081/mcp` — see [`docs/ARCHITECTURE.md`](ARCHITECTURE.md#mcp-server) for the tool
surface and design rationale. No separate process or port is needed; it's served by the same
Spring Boot app as the REST API, once the app is running (steps 1-4 above).

To connect Claude Code to a locally running instance:

```shell
claude mcp add --transport http docman-ai http://localhost:8081/mcp --scope local
```

Then start a **new** Claude Code session in this project — MCP servers are loaded at session start,
so a server added while a session is already running won't appear in that session's tools until it's
restarted. Verify the connection and tool list with:

```shell
claude mcp list
claude mcp get docman-ai
```

Any other MCP client (e.g. the [MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector),
`npx @modelcontextprotocol/inspector`) works the same way against the same URL, using the
streamable-HTTP transport.

## Production profile (OpenAI)

`docman-api` bundles both the Ollama and OpenAI Spring AI starters, and the runnable jar doesn't
need rebuilding to switch between them — `spring.ai.model.chat` / `spring.ai.model.embedding`
(default: `ollama`) decide which starter's autoconfiguration actually wires up the
`ChatModel`/`EmbeddingModel` beans. The `prod` profile
(`docman-api/src/main/resources/application-prod.yaml`) flips both to `openai` and configures:

- `spring.ai.openai.api-key`: read from the `OPENAI_API_KEY` environment variable — set this before
  starting the app, it is not checked into any config file
- `spring.ai.openai.chat.options.model: gpt-5.4-mini` — replaces `llama3.1` for RAG answers,
  summarization, and classification
- `spring.ai.openai.embedding.options.model: text-embedding-3-small` — replaces `nomic-embed-text`
  for chunk embeddings
- `spring.ai.vectorstore.opensearch.index-name: docman-vector-index-openai` and `.dimensions: 1536`
  — a **separate** OpenSearch index from the default `docman-vector-index` (768-dim), since
  OpenSearch won't let you change an existing field's `knn_vector` dimension in place. Documents
  ingested under the default profile are not visible through this index; either re-run ingestion
  for existing documents against the `prod` profile, or keep the two profiles pointed at genuinely
  separate environments.

Run with the profile active:

```shell
export OPENAI_API_KEY=sk-...
mvn -pl docman-api -am spring-boot:run -Dspring-boot.run.profiles=prod
# or
java -jar docman-api/target/docman-api-1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

Everything else (MinIO, MongoDB, Temporal, Kafka, the OpenSearch connection itself) is unaffected
by this profile — only the model provider and vector index change.

## Deployment notes

- **`docman-api`'s jar is the only deployable artifact.** The other four modules exist purely to
  organize the codebase at compile time; nothing about the module split affects how the
  application is deployed or run.
- **Devtools caveat**: `docman-api` includes `spring-boot-devtools` for local hot-reload. Be aware
  that DevTools' in-process restart does not always reliably reload changes to Temporal
  workflow/activity classes across many stacked edits — if workflow behavior looks stale after a
  hot-reload during development, do a full process restart before trusting what you're seeing.
- **State is durable across restarts**: MongoDB, MinIO, and OpenSearch data all persist in Docker
  volumes (`mongo_data`, `minio-volume`, `opensearch-data`) defined in `docker-compose.yml`. Tearing
  down and recreating the app container (or restarting the jar) does not lose ingested documents.
- **Fresh-environment landmine to be aware of**: `spring.ai.vectorstore.opensearch.dimensions` must
  match the embedding model's actual output dimensionality (768 for `nomic-embed-text`) *before*
  the index is first created — `initialize-schema: true` only applies the mapping when the index
  doesn't already exist. If you ever swap embedding models, either use a fresh index name or
  reindex, since OpenSearch won't let you change an existing field's `knn_vector` dimension.
- **No authentication** is implemented on the REST API itself — this is a reference implementation.
  Put it behind your own gateway/auth layer before exposing it beyond local development.

## Troubleshooting

- **Port 8088 already in use on startup**: the bundled `docman-temporal` container also publishes
  host port 8088. Run the app on a different port: `mvn -pl docman-api -am spring-boot:run
  -Dspring-boot.run.arguments=--server.port=8090` (or `java -jar ... --server.port=8090`).
- **`PKIX path building failed` / SSL handshake errors talking to OpenSearch**: this shouldn't
  happen against the bundled `docker-compose.yml` OpenSearch (see step 4 above — trust is
  automatic via a checked-in SSL bundle). If you hit it anyway, confirm
  `docman-api/src/main/resources/opensearch-root-ca.pem` is actually on the runtime classpath, and
  that `spring.ai.vectorstore.opensearch.ssl-bundle: opensearch` in `application.yaml` hasn't been
  removed/overridden. If you've pointed the app at a different OpenSearch instance with its own
  certificate, you'll need to swap in that instance's CA certificate instead.
- **`/api/v1/document/ask` or summary generation seems to hang**: Ollama chat calls on CPU-only hardware
  can legitimately take minutes. Check `ollama ps` to confirm the model is loaded and actively
  processing (100% CPU) rather than stuck.
- **A document never leaves `CREATED`**: for the presigned-URL flow (`POST /api/v1/document`), the
  workflow is waiting for content to actually land in MinIO — confirm the client actually performed
  the `PUT` to the returned presigned URL. The workflow gives up after 15 minutes and marks the
  document `FAILED`.
- **Mongo repository not picking up documents**: confirm `Application`'s
  `@EnableMongoRepositories(basePackages = "org.kamranzafar.docman.repository")` matches wherever
  `DocumentMetadataRepository` actually lives — this has drifted before during refactors.
- **App fails to start with `BeanCreationException: Error creating bean with name
  'openAiAudioSpeechModel'` (or `...TranscriptionModel`/`...ImageModel`/`...ModerationModel`) /
  "OpenAI API key must be set"**: this means one of the `spring.ai.model.audio.speech` /
  `.audio.transcription` / `.image` / `.moderation` properties described above was removed or
  overridden. Both Ollama and OpenAI starters are always on the classpath (see
  [Production profile (OpenAI)](#production-profile-openai)), and unlike `spring.ai.model.chat`
  /`.embedding`, these four OpenAI autoconfigurations activate by default
  (`matchIfMissing=true`) when their property is unset — regardless of the active profile or
  which provider `chat`/`embedding` point at. Restore the four `none` values in
  `application.yaml`, or set `OPENAI_API_KEY` if you actually intend to use one of those
  capabilities.
- **Every OpenAI chat call (`prod` profile) fails with `JsonEOFException` / "Unexpected
  end-of-input" after the response's opening `{`**: this was hit and root-caused during initial
  end-to-end testing of the `prod` profile in this environment — a gzip-encoded response from
  OpenAI's API was getting truncated somewhere in the RestClient/Micrometer-observation stack
  before Jackson could parse it, even though the exact same request succeeds when made with a
  bare JDK `HttpClient` or `curl`. The fix already in place is the `RestClient.Builder` bean in
  `DocmanConfig` that forces `Accept-Encoding: identity` (no compression) for all outbound model
  calls. If you see this error again after changing HTTP client dependencies, check that this bean
  is still being picked up (Spring AI's model autoconfigurations use whatever `RestClient.Builder`
  bean is in context, see the comment on that bean for more detail).
- **App fails to start with a `BeanCurrentlyInCreationException`/circular-reference error
  mentioning `documentSearchServiceImpl`, `chatClientBuilder`, `toolCallbackResolver`, and a tool
  bean from `docman-mcp-server`**: this means a new MCP tool object (or a change to
  `McpToolConfiguration`) started registering tools as a `ToolCallbackProvider`/`ToolCallback`
  bean instead of a `List<McpServerFeatures.SyncToolSpecification>` bean. See [the bean-wiring
  gotcha in `docs/ARCHITECTURE.md`](ARCHITECTURE.md#bean-wiring-gotcha-synctoolspecification-not-toolcallbackprovider)
  for why that specific bean type matters here.
- **A locally running `docman-ai` MCP server shows `claude mcp list` as `✔ Connected` but its
  tools don't show up in Claude Code**: MCP servers are loaded when a Claude Code session starts,
  not hot-reloaded into an already-running session. Start a new session after running `claude mcp
  add` (see [MCP Server (agent access)](#mcp-server-agent-access) above).
