# API Reference

Base URL: `http://localhost:8088` (default; see [`docs/SETUP.md`](SETUP.md#configuration-reference)
for how the port is configured). All endpoints are under `/document`. There is no authentication —
this is a reference implementation.

## Conventions

### Document representation

Every endpoint that returns a document uses this shape (`DocumentDto`):

```json
{
  "id": "a391f59e-f0fb-4d98-a36c-9f7706cebb8a",
  "name": "invoice-123.pdf",
  "contentType": "application/pdf",
  "status": "INDEXED",
  "documentType": "invoices",
  "metadata": {
    "vendor": "acme",
    "summary": "A short AI-generated summary of the document, added once ingestion completes."
  }
}
```

`status` is one of `CREATED`, `UPLOADED`, `UPDATED`, `INDEXED`, `FAILED` (see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-lifecycle)). `documentType` starts out as
whatever the caller optionally supplied at creation, then gets overwritten once ingestion finishes:
an AI classification step (see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-classification)) assigns one of `statements`,
`invoices`, `policy documents`, `compliance certificates`, `insurance documents`, `contracts`, or
`unknown` if the document doesn't clearly match any of those. `metadata` is an arbitrary map
supplied by the caller at creation time; the system adds a `summary` key to it once AI
summarization completes.

### Error responses

All errors return an [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) `ProblemDetail`
body:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Document not found",
  "instance": "/document/metadata"
}
```

| Status | When |
|--------|------|
| 400    | Validation failure (missing/blank required field, malformed UUID, empty search filters, too many filters, missing question) |
| 404    | Document id doesn't exist |
| 500    | Unhandled server error |

---

## `POST /document` — create a document, get a presigned upload URL

Creates the document record and returns a MinIO presigned **PUT** URL. The client uploads the
file directly to that URL; a Temporal workflow then picks up the upload asynchronously and runs it
through ingestion. Use this path for large files or when you want the upload to happen directly
from a browser/client without proxying bytes through this API.

**Request body** (`DocumentRequest`):

```json
{
  "name": "invoice-123.pdf",
  "contentType": "application/pdf",
  "documentType": "invoice",
  "metadata": { "vendor": "acme" }
}
```

| Field         | Required | Notes |
|---------------|----------|-------|
| `name`        | yes      | Used as part of the object key in MinIO |
| `contentType` | yes      | Stored and used as the `Content-Type` on download |
| `documentType`| no       | Initial tag, if supplied; overwritten by AI classification once ingestion completes |
| `metadata`    | no       | Arbitrary key/value map |

**Response** `200 OK`:

```json
{
  "url": "http://localhost:9000/docman/a391f59e-.../invoice-123.pdf?X-Amz-Algorithm=...",
  "document": { "...": "DocumentDto, status: CREATED" }
}
```

**Then upload the content** to the returned URL with an HTTP `PUT` (not `POST`) — the URL is
signed for `PUT` only:

```shell
curl -X PUT "<url>" --data-binary @invoice-123.pdf
```

```shell
curl -X POST http://localhost:8088/document \
  -H "Content-Type: application/json" \
  -d '{"name":"invoice-123.pdf","contentType":"application/pdf","documentType":"invoice","metadata":{"vendor":"acme"}}'
```

---

## `PUT /document` — create and upload in one request

Synchronous alternative: the file is sent directly in the request body. Content streams straight
to MinIO without being buffered into memory server-side. Use this for smaller files or simpler
clients that can't do a separate presigned upload step.

**Request**: `multipart/form-data` with two parts:

| Part       | Type          | Notes |
|------------|---------------|-------|
| `file`     | file          | The document content; its filename and content-type become the document's `name`/`contentType` |
| `metadata` | `application/json` | Arbitrary key/value map (same as `DocumentRequest.metadata`) |

Note: `documentType` cannot be supplied via this endpoint (only via `POST /document`); it stays
`null` until AI classification sets it once ingestion completes.

**Response** `200 OK`: same `DocumentResponse` shape as above, but `status` starts at `CREATED` and
the workflow (already having its content) proceeds straight through without waiting.

```shell
curl -X PUT http://localhost:8088/document \
  -F "file=@invoice-123.pdf;type=application/pdf" \
  -F 'metadata={"vendor":"acme"};type=application/json'
```

Max upload size: 100MB (`spring.servlet.multipart.max-file-size`/`max-request-size`).

---

## `GET /document/metadata/{id}` — fetch a document's metadata

**Path parameter**: `id` — the document UUID.

**Response** `200 OK` (`DocumentSearchResponse`):

```json
{ "documents": [ { "...": "DocumentDto" } ] }
```

**Errors**: `404` if the id doesn't exist, `400` if it's not a valid UUID.

```shell
curl "http://localhost:8088/document/metadata/a391f59e-f0fb-4d98-a36c-9f7706cebb8a"
```

---

## `GET /document/content/{id}` — get a presigned download URL

**Path parameter**: `id` — the document UUID.

**Response** `200 OK`:

```json
{ "url": "http://localhost:9000/docman/a391f59e-.../invoice-123.pdf?response-content-type=...&X-Amz-..." }
```

The URL is signed for `GET` only and expires after `minio.presigned.download-url-expiry` (default
300s). Fetching it before the document's content has actually been uploaded returns a MinIO
`NoSuchKey` error (404) rather than the app's own error shape, since that request goes straight to
MinIO, not through the Docman API.

```shell
curl "http://localhost:8088/document/content/a391f59e-f0fb-4d98-a36c-9f7706cebb8a"
# then:
curl "<returned url>" -o invoice-123.pdf
```

---

## `POST /document/ask` — ask a question (RAG)

Vector similarity search over indexed document chunks, with `llama3.1` generating an answer
grounded in the retrieved context.

**Request body** (`DocumentSearchRequest`):

```json
{ "question": "What does the invoice from acme cover?" }
```

**Response** `200 OK`:

```json
{ "answer": "..." }
```

This endpoint can take a long time — Ollama chat inference on CPU-only hardware has been observed
taking anywhere from ~30 seconds to several minutes depending on document size and hardware. The
request is handled asynchronously server-side (`DeferredResult` on a dedicated executor) so it
doesn't tie up a web server thread while waiting; the client still simply waits for the HTTP
response. If it takes longer than ~650 seconds the server returns `503 Service Unavailable` with a
timeout message.

**Errors**: `400` if `question` is blank/missing.

```shell
curl -X POST http://localhost:8088/document/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"What does the invoice from acme cover?"}'
```

---

## `POST /document/search` — structured metadata search

Filters documents by exact metadata field values — no need to know OpenSearch query syntax, and no
way to reach fields outside the `metadata` subtree (the server always resolves a filter key `k` to
the OpenSearch field `metadata.k`).

**Request body** (`DocumentSearchRequest`):

```json
{ "filters": { "documentType": "invoice", "vendor": "acme" } }
```

Multiple filters are combined with AND semantics. Maximum 10 filters per request
(`QueryConstants.QUERY_MAX_FILTERS`).

Note: a `documentType` filter here matches the value present in the chunk's indexed metadata,
which is a snapshot taken at indexing time — i.e. whatever the caller supplied in `POST /document`,
not the value the AI classifier assigns afterward (see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-classification)). To look up a document's
AI-classified type, use `GET /document/metadata/{id}` instead.

**Response** `200 OK`: a list of matching chunks' metadata (one entry per indexed chunk, collapsed
by parent document where possible):

```json
[
  {
    "metadata": {
      "chunk_index": 0,
      "documentType": "invoice",
      "vendor": "acme",
      "parent_document_id": "a391f59e-f0fb-4d98-a36c-9f7706cebb8a",
      "total_chunks": 3
    }
  }
]
```

**Errors**: `400` if `filters` is missing/empty, or if it has more than 10 entries. `404` if
nothing matches.

```shell
curl -X POST http://localhost:8088/document/search \
  -H "Content-Type: application/json" \
  -d '{"filters":{"documentType":"invoice"}}'
```

---

## `DELETE /document/{id}` — delete a document

Tears down everything associated with a document:

1. Terminates its Temporal ingestion workflow, if one is still running
2. Removes its chunks from the OpenSearch vector store
3. Removes its content from MinIO
4. Removes its record from MongoDB

**Path parameter**: `id` — the document UUID.

**Response**: `204 No Content` on success.

**Errors**: `404` if the id doesn't exist, `400` if it's not a valid UUID. Deleting a document
whose workflow already finished, or whose content was never uploaded, is handled gracefully (both
are no-ops for the respective cleanup step, not errors).

```shell
curl -X DELETE "http://localhost:8088/document/a391f59e-f0fb-4d98-a36c-9f7706cebb8a"
```

---

## Endpoint summary

| Method   | Path                     | Purpose                                      |
|----------|--------------------------|-----------------------------------------------|
| `POST`   | `/document`              | Create + presigned upload URL                 |
| `PUT`    | `/document`              | Create + direct multipart upload              |
| `GET`    | `/document/metadata/{id}` | Fetch document metadata                       |
| `GET`    | `/document/content/{id}`  | Presigned download URL                        |
| `POST`   | `/document/ask`          | RAG question answering                        |
| `POST`   | `/document/search`      | Structured metadata search                    |
| `DELETE` | `/document/{id}`         | Delete document (full cleanup)                |

A [Bruno](https://www.usebruno.com/) collection covering all of these (plus direct MinIO/Ollama/
OpenSearch debug requests) is in the `bruno/` directory at the repository root.
