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

This is a 5-module Maven reactor (`docman-domain`, `docman-persistence`, `docman-service`,
`docman-workflow`, `docman-api`). Build the whole thing from the repository root:

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

The `spring-boot-maven-plugin` config passes two JVM system properties sourced from environment
variables — `SSL_KEYSTORE` and `SSL_KEYSTORE_PASS` — used as the Java trust store for the OpenSearch
TLS connection (OpenSearch's Docker image uses a self-signed certificate by default). If you're
running the jar directly rather than via `spring-boot:run`, set these yourself:

```shell
java -Djavax.net.ssl.trustStore=$SSL_KEYSTORE \
     -Djavax.net.ssl.trustStorePassword=$SSL_KEYSTORE_PASS \
     -jar docman-api/target/docman-api-1.0-SNAPSHOT.jar
```

Your trust store needs to trust OpenSearch's certificate; import it if you haven't already
(consult your JDK's `keytool` documentation, or point `SSL_KEYSTORE` at a store that already trusts
it).

Verify the app is up:

```shell
curl -s http://localhost:8088/actuator/health
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
| `spring.ai.vectorstore.opensearch.dimensions`                        | `768`                             | Must match the embedding model's output size (`nomic-embed-text` = 768) |
| `spring.ai.ollama.base-url`                                            | `http://localhost:11434/`         | Ollama endpoint |
| `spring.ai.ollama.embedding.options.model`                               | `nomic-embed-text`                | Embedding model |
| `spring.ai.ollama.chat.model`                                               | `llama3.1`                        | Chat model, used for both RAG answers and summarization |
| `spring.ai.ollama.chat.options.timeout`                                       | `600s`                            | Max time for a single Ollama chat call |
| `spring.servlet.multipart.max-file-size` / `.max-request-size`                  | `100MB`                           | Direct (`PUT /document`) upload size cap |
| `kafka.address`                                                                    | `localhost:9092`                  | Kafka bootstrap server |
| `kafka.topic`                                                                        | `documents`                       | Lifecycle notification topic |

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
- **`PKIX path building failed` / SSL handshake errors talking to OpenSearch**: your JVM's trust
  store doesn't trust OpenSearch's self-signed certificate. Make sure `SSL_KEYSTORE` /
  `SSL_KEYSTORE_PASS` point at a trust store that has it imported (see step 4 above).
- **`/document/ask` or summary generation seems to hang**: Ollama chat calls on CPU-only hardware
  can legitimately take minutes. Check `ollama ps` to confirm the model is loaded and actively
  processing (100% CPU) rather than stuck.
- **A document never leaves `CREATED`**: for the presigned-URL flow (`POST /document`), the
  workflow is waiting for content to actually land in MinIO — confirm the client actually performed
  the `PUT` to the returned presigned URL. The workflow gives up after 15 minutes and marks the
  document `FAILED`.
- **Mongo repository not picking up documents**: confirm `Application`'s
  `@EnableMongoRepositories(basePackages = "org.kamranzafar.docman.repository")` matches wherever
  `DocumentMetadataRepository` actually lives — this has drifted before during refactors.
