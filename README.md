# Docman AI

A reference implementation of an AI-enabled document management system and RAG (Retrieval-Augmented
Generation) knowledge base, built as a multi-module Spring Boot application orchestrated by Temporal.

Documents are uploaded to object storage, tracked in MongoDB, and asynchronously processed by a
Temporal workflow that extracts their text, generates vector embeddings, indexes them for search,
produces an AI-generated summary, and classifies the document into a fixed set of types — all
without blocking the client that uploaded the document.

## Features

- **Document upload**, either directly (multipart) or via presigned URLs against MinIO, so large
  files can be streamed straight to object storage without passing through the API server
- **Asynchronous ingestion workflow** (Temporal) that waits for the upload to land, extracts text
  with Apache Tika, chunks and embeds it, and indexes it — with automatic retries and a durable,
  thread-cheap wait for slow or delayed uploads
- **AI summarization and classification**: once indexing completes, two RAG queries over the
  document's own indexed chunks run concurrently with each other — one generates a short summary,
  the other assigns `documentType` (one of `statements`, `invoices`, `policy documents`,
  `compliance certificates`, `insurance documents`, `contracts`, or `unknown`) — each publishing a
  Kafka event once done; a failed or slow summary/classification never blocks the document from
  reaching `INDEXED`
- **Vector search / RAG**: ask natural-language questions and get answers grounded in the indexed
  document content (`nomic-embed-text` embeddings, OpenSearch as the vector store, `llama3.1` chat)
- **Structured metadata search**: filter documents by arbitrary metadata fields (including document
  type and free-form tags) without needing to know OpenSearch's query syntax
- **Kafka-triggered vector store metadata sync**: every document metadata update (summary results,
  classification, or future direct edits) publishes to a Kafka topic that a consumer picks up to
  patch the corresponding OpenSearch chunks' metadata in place — no re-embedding, no re-reading the
  file
- **Full lifecycle management**: presigned/direct download, metadata lookup, and delete (which tears
  down the MinIO object, the vector store entries, the Mongo record, and cancels any still-running
  ingestion workflow for that document)
- **Kafka event notifications** for every stage of a document's ingestion lifecycle

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how these pieces fit together, and
[`docs/API.md`](docs/API.md) for the full REST API reference.

## Technology Stack

| Concern                  | Technology                          |
|---------------------------|--------------------------------------|
| Language / runtime        | Java 21                             |
| Application framework      | Spring Boot 3.5                     |
| Object storage             | MinIO                                |
| Metadata store              | MongoDB                              |
| Vector store                | OpenSearch                          |
| Workflow orchestration      | Temporal                             |
| Eventing                    | Kafka                                |
| AI inference                | Ollama (`llama3.1`, `nomic-embed-text`) |
| DTO ↔ entity mapping        | MapStruct                            |
| Build                        | Maven (multi-module)                |

Full details, including how the five Maven modules divide responsibilities, are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Quick Start

```shell
# 1. Install Ollama and pull the required models
brew install ollama
ollama pull nomic-embed-text
ollama pull llama3.1

# 2. Start MinIO, MongoDB, OpenSearch, Temporal, and Kafka
docker-compose -f docker/docker-compose.yml up -d

# 3. Build the whole multi-module project
mvn clean package

# 4. Run the application (the runnable module is docman-api)
mvn -pl docman-api -am spring-boot:run
```

The API is served on `http://localhost:8088` by default (see
[`docs/SETUP.md`](docs/SETUP.md#configuration-reference) for how to change it, including a note
about a port clash with the bundled Temporal container).

Full setup, configuration, and deployment instructions: [`docs/SETUP.md`](docs/SETUP.md).

## Trying the API

A [Bruno](https://www.usebruno.com/) collection is included in `bruno/` covering every endpoint,
plus example requests against MinIO, Ollama, and OpenSearch directly for debugging. See
[`docs/API.md`](docs/API.md) for the full request/response reference if you'd rather use `curl`.

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module structure, ingestion workflow, data
  model, and how search/RAG work
- [`docs/SETUP.md`](docs/SETUP.md) — prerequisites, local setup, configuration reference, and
  deployment notes
- [`docs/API.md`](docs/API.md) — REST API reference with request/response examples

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
