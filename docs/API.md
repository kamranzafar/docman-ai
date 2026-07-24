# API Reference

Base URL: `http://localhost:8088` (default; see [`docs/SETUP.md`](SETUP.md#configuration-reference)
for how the port is configured). All endpoints are under `/api/v1/document`. There is no authentication —
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
  },
  "createdAt": "2026-07-24T05:01:43.877Z",
  "createdBy": "alice",
  "updatedAt": "2026-07-24T05:01:43.877Z",
  "updatedBy": "alice",
  "version": 1
}
```

`status` is one of `CREATED`, `UPLOADED`, `UPDATED`, `INDEXED`, `FAILED` (see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-lifecycle)). `documentType` starts out as
whatever the caller optionally supplied at creation, then gets overwritten once ingestion finishes:
an AI classification step (see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-summarization--classification)) assigns one of `statements`,
`invoices`, `policy documents`, `compliance certificates`, `insurance documents`, `contracts`, or
`unknown` if the document doesn't clearly match any of those. `metadata` is an arbitrary map
supplied by the caller at creation time; the system adds a `summary` key to it once AI
summarization completes.

`createdAt`/`updatedAt` are set by the system (UTC timestamps); `createdBy`/`updatedBy` are
optional, caller-supplied identifiers (e.g. a username), and are otherwise `null`. `version`
starts at `1` and only increments on a user-driven change via `PUT /api/v1/document/{id}` — see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-versioning--revision-history) for exactly what
does and doesn't bump it.

### Error responses

All errors return an [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) `ProblemDetail`
body:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Document not found",
  "instance": "/api/v1/document/metadata"
}
```

| Status | When |
|--------|------|
| 400    | Validation failure (missing/blank required field, malformed UUID, empty search filters, too many filters, missing question) |
| 404    | Document id doesn't exist |
| 409    | An update (`POST`/`PUT /api/v1/document/{id}`) lost an optimistic-concurrency race against another concurrent update to the same document — reload and retry |
| 500    | Unhandled server error |

---

## `POST /api/v1/document` — create a document, get a presigned upload URL

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
  "metadata": { "vendor": "acme" },
  "createdBy": "alice"
}
```

| Field         | Required | Notes |
|---------------|----------|-------|
| `name`        | yes      | Used as part of the object key in MinIO |
| `contentType` | yes      | Stored and used as the `Content-Type` on download |
| `documentType`| no       | Initial tag, if supplied; overwritten by AI classification once ingestion completes |
| `metadata`    | no       | Arbitrary key/value map |
| `createdBy`   | no       | Optional caller identifier, stored as-is on `createdBy`/`updatedBy` |

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
curl -X POST http://localhost:8088/api/v1/document \
  -H "Content-Type: application/json" \
  -d '{"name":"invoice-123.pdf","contentType":"application/pdf","documentType":"invoice","metadata":{"vendor":"acme"}}'
```

---

## `PUT /api/v1/document` — create and upload in one request

Synchronous alternative: the file is sent directly in the request body. Content streams straight
to MinIO without being buffered into memory server-side. Use this for smaller files or simpler
clients that can't do a separate presigned upload step.

**Request**: `multipart/form-data` with three parts:

| Part        | Type          | Notes |
|-------------|---------------|-------|
| `file`      | file          | The document content; its filename and content-type become the document's `name`/`contentType` |
| `metadata`  | `application/json` | Arbitrary key/value map (same as `DocumentRequest.metadata`) |
| `createdBy` | text, optional | Same as `DocumentRequest.createdBy` |

Note: `documentType` cannot be supplied via this endpoint (only via `POST /api/v1/document`); it stays
`null` until AI classification sets it once ingestion completes.

**Response** `200 OK`: same `DocumentResponse` shape as above, but `status` starts at `CREATED` and
the workflow (already having its content) proceeds straight through without waiting.

```shell
curl -X PUT http://localhost:8088/api/v1/document \
  -F "file=@invoice-123.pdf;type=application/pdf" \
  -F 'metadata={"vendor":"acme"};type=application/json' \
  -F "createdBy=alice"
```

Max upload size: 100MB (`spring.servlet.multipart.max-file-size`/`max-request-size`).

---

## `POST /api/v1/document/{id}` — update to a new file version, get a presigned upload URL

The presigned counterpart to `PUT /api/v1/document/{id}`, mirroring how `POST /api/v1/document` relates to
`PUT /api/v1/document`: it always expects a new file version — bumps `version`, resets `status` to
`CREATED`, clears the previous version's vector store chunks, returns a presigned **PUT** URL for
the new version's file, and starts a new workflow execution that (like creation) polls MinIO until
the client's upload lands. For a metadata-only change, use `PUT /api/v1/document/{id}` without a `file`
part instead — this endpoint is only for the "new file" case, since `name`/`contentType` must be
known upfront to build the presigned URL.

**Path parameter**: `id` — the document UUID.

**Request body** (`DocumentUpdateRequest`, with `name`/`contentType` required here):

```json
{
  "name": "invoice-123-corrected.pdf",
  "contentType": "application/pdf",
  "metadata": { "vendor": "acme" },
  "updatedBy": "bob"
}
```

**Response** `200 OK`:

```json
{
  "url": "http://localhost:9000/docman/a391f59e-.../2/invoice-123-corrected.pdf?X-Amz-Algorithm=...",
  "document": { "...": "DocumentDto, status: CREATED, version: 2" }
}
```

**Then upload the content** to the returned URL with an HTTP `PUT`, same as the create flow:

```shell
curl -X PUT "<url>" --data-binary @invoice-123-corrected.pdf
```

**Errors**: `404` if the id doesn't exist, `400` if `name` or `contentType` is missing, `409` if
this update lost a concurrency race against another update to the same document (see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-versioning--revision-history)) — reload and
retry.

```shell
curl -X POST http://localhost:8088/api/v1/document/a391f59e-f0fb-4d98-a36c-9f7706cebb8a \
  -H "Content-Type: application/json" \
  -d '{"name":"invoice-123-corrected.pdf","contentType":"application/pdf","metadata":{"vendor":"acme"},"updatedBy":"bob"}'
```

---

## `PUT /api/v1/document/{id}` — update metadata, or metadata and file

The direct-upload counterpart to `POST /api/v1/document/{id}` above — use this one for a metadata-only
change (no file part needed), or to replace the file synchronously in the same request instead of
via a presigned URL. Updates an existing document's metadata, and optionally replaces its file
content. Either way,
`version` increments by 1 and a snapshot of the new state is recorded in the revision history —
see [`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-versioning--revision-history) for the full
version/revision model, the exact chunk-cleanup + reindexing behavior when a file is included, and
why `metadata` is a **full replacement**, not a merge, of the existing map.

**Path parameter**: `id` — the document UUID.

**Request**: `multipart/form-data` with two parts:

| Part       | Type          | Required | Notes |
|------------|---------------|----------|-------|
| `metadata` | `application/json` (`DocumentUpdateRequest`) | yes | `{ "metadata": {...}, "documentType": "...", "updatedBy": "..." }` — all three fields optional, but the request must set at least one of `metadata`/`documentType`, or include a `file` part |
| `file`     | file, optional | no  | If present, replaces the document's content and re-runs the full ingestion workflow (index, summarize, classify) for the new version |

**Response** `200 OK`: `DocumentResponse` wrapping the updated `DocumentDto` (no `url` field — this
isn't a presigned upload, the file is streamed straight to MinIO like `PUT /api/v1/document`).

**Errors**: `404` if the id doesn't exist, `400` if the request has none of `metadata`,
`documentType`, or `file`, `409` if this update lost a concurrency race against another update to
the same document (see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-versioning--revision-history)) — reload and
retry.

Metadata-only:

```shell
curl -X PUT http://localhost:8088/api/v1/document/a391f59e-f0fb-4d98-a36c-9f7706cebb8a \
  -F 'metadata={"metadata":{"vendor":"acme","region":"us"},"updatedBy":"bob"};type=application/json'
```

Metadata + a new file (bumps `version`, resets `status` to `CREATED`, and starts a new workflow
run):

```shell
curl -X PUT http://localhost:8088/api/v1/document/a391f59e-f0fb-4d98-a36c-9f7706cebb8a \
  -F 'metadata={"metadata":{"vendor":"acme"},"updatedBy":"bob"};type=application/json' \
  -F "file=@invoice-123-corrected.pdf;type=application/pdf"
```

---

## `GET /api/v1/document/metadata/{id}` — fetch a document's metadata

**Path parameters**: `id` — the document UUID. Optional trailing `version` — a specific past
version's snapshot instead of the latest, read from the revision history (see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md#document-versioning--revision-history)). A version
snapshot's `status` is always `null` — it's not a live-workflow concept.

**Response** `200 OK` (`DocumentSearchResponse`):

```json
{ "documents": [ { "...": "DocumentDto" } ] }
```

**Errors**: `404` if the id doesn't exist (or, with a version given, if that version doesn't
exist), `400` if the id isn't a valid UUID.

```shell
curl "http://localhost:8088/api/v1/document/metadata/a391f59e-f0fb-4d98-a36c-9f7706cebb8a"
# a specific past version:
curl "http://localhost:8088/api/v1/document/metadata/a391f59e-f0fb-4d98-a36c-9f7706cebb8a/1"
```

---

## `GET /api/v1/document/content/{id}` — get a presigned download URL

**Path parameters**: `id` — the document UUID. Optional trailing `version` — a specific past
version's file instead of the latest.

**Response** `200 OK`:

```json
{ "url": "http://localhost:9000/docman/a391f59e-.../1/invoice-123.pdf?response-content-type=...&X-Amz-..." }
```

The URL is signed for `GET` only and expires after `minio.presigned.download-url-expiry` (default
300s). Fetching it before the document's content has actually been uploaded returns a MinIO
`NoSuchKey` error (404) rather than the app's own error shape, since that request goes straight to
MinIO, not through the Docman API. With an explicit `version`, the app checks MinIO existence
itself first and returns its own `404` if that version never had a file uploaded (e.g. a
metadata-only revision) — the latest-version path doesn't do this extra check.

```shell
curl "http://localhost:8088/api/v1/document/content/a391f59e-f0fb-4d98-a36c-9f7706cebb8a"
# then:
curl "<returned url>" -o invoice-123.pdf
# a specific past version:
curl "http://localhost:8088/api/v1/document/content/a391f59e-f0fb-4d98-a36c-9f7706cebb8a/1"
```

---

## `POST /api/v1/document/ask` — ask a question (RAG)

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
curl -X POST http://localhost:8088/api/v1/document/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"What does the invoice from acme cover?"}'
```

---

## `POST /api/v1/document/search` — structured metadata search

Filters documents by exact metadata field values — no need to know OpenSearch query syntax, and no
way to reach fields outside the `metadata` subtree (the server always resolves a filter key `k` to
the OpenSearch field `metadata.k`).

**Request body** (`DocumentSearchRequest`):

```json
{ "filters": { "documentType": "invoice", "vendor": "acme" } }
```

Multiple filters are combined with AND semantics. Maximum 10 filters per request
(`QueryConstants.QUERY_MAX_FILTERS`).

Note: a `documentType`/metadata filter here matches the chunk's indexed metadata, which is kept in
sync with Mongo asynchronously via Kafka (see
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md#keeping-vector-store-metadata-in-sync)) — so it can lag the
`GET /api/v1/document/metadata/{id}` value by up to a couple of seconds right after ingestion completes.

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
curl -X POST http://localhost:8088/api/v1/document/search \
  -H "Content-Type: application/json" \
  -d '{"filters":{"documentType":"invoice"}}'
```

---

## `DELETE /api/v1/document/{id}` — delete a document

Tears down everything associated with a document, **all versions included**:

1. Terminates the current version's Temporal ingestion workflow, if one is still running
2. Removes its chunks from the OpenSearch vector store
3. Removes every version's content from MinIO (not just the latest)
4. Removes its record, and its entire revision history, from MongoDB

**Path parameter**: `id` — the document UUID.

**Response**: `204 No Content` on success.

**Errors**: `404` if the id doesn't exist, `400` if it's not a valid UUID. Deleting a document
whose workflow already finished, or whose content was never uploaded, is handled gracefully (both
are no-ops for the respective cleanup step, not errors).

```shell
curl -X DELETE "http://localhost:8088/api/v1/document/a391f59e-f0fb-4d98-a36c-9f7706cebb8a"
```

---

## Endpoint summary

| Method   | Path                                          | Purpose                                              |
|----------|-----------------------------------------------|-------------------------------------------------------|
| `POST`   | `/api/v1/document`                            | Create + presigned upload URL                          |
| `PUT`    | `/api/v1/document`                            | Create + direct multipart upload                       |
| `POST`   | `/api/v1/document/{id}`                       | Update to a new file version + presigned upload URL    |
| `PUT`    | `/api/v1/document/{id}`                       | Update metadata, or metadata + direct file upload (new version) |
| `GET`    | `/api/v1/document/metadata/{id}[/{version}]`  | Fetch document metadata (latest or a specific version) |
| `GET`    | `/api/v1/document/content/{id}[/{version}]`   | Presigned download URL (latest or a specific version)  |
| `POST`   | `/api/v1/document/ask`                        | RAG question answering                                 |
| `POST`   | `/api/v1/document/search`                     | Structured metadata search                              |
| `DELETE` | `/api/v1/document/{id}`                       | Delete document, all versions (full cleanup)           |

A [Bruno](https://www.usebruno.com/) collection covering all of these (plus direct MinIO/Ollama/
OpenSearch debug requests) is in the `bruno/` directory at the repository root.
