# AI Security (OWASP LLM Top 10)

This document records where Docman AI stands against the
[OWASP Top 10 for LLM Applications (2025)](https://genai.owasp.org/llm-top-10/): which risks apply,
which controls are implemented, and which gaps are deliberately left open.

This is a reference implementation, not a hardened product. The controls below are defense-in-depth
measures, not guarantees — in particular, none of them replace authentication and authorization,
which this project does not have (see [Accepted limitations](#accepted-limitations)).

## Summary

| # | Risk | Status | Implementation details |
|---|------|--------|------------------------|
| LLM01 | Prompt Injection | Mitigated | Defense-in-depth: `PromptGuardrails` system message + delimited untrusted content on every LLM call |
| LLM02 | Sensitive Information Disclosure | Mitigated | Unhandled errors return a generic message + reference id; infra exception text is logged, never returned |
| LLM03 | Supply Chain | Partial | Dependency versions are pinned; no SBOM / signature verification |
| LLM04 | Data & Model Poisoning | Partial | Injection controls (LLM01) apply to ingested content; indexing input is size-capped. Any principal that can upload can still influence RAG answers |
| LLM05 | Improper Output Handling | Mitigated | Classification maps to a fixed enum (`DocumentType.fromLabel`); summary output is length-capped. API responses are JSON, rendered by the client |
| LLM06 | Excessive Agency | Low | The MCP tool surface is read-only by design (no create/update/delete/restore); the internal RAG `ChatClient` has no tool callbacks |
| LLM07 | System Prompt Leakage | Not a risk | The system prompt contains no secrets or access rules |
| LLM08 | Vector & Embedding Weaknesses | Partial | `deleted == false` is force-ANDed into every RAG/search query; **no tenant isolation or per-document authorization** (see Accepted limitations) |
| LLM09 | Misinformation | Low | RAG is grounded ("using only the information above… if the answer is not contained in the context, say that you can't answer"); classification is enum-constrained |
| LLM10 | Unbounded Consumption | Mitigated | Per-IP rate limit, bounded `/ask` executor, output-token caps, input-size caps |

## Prompt-injection mitigations (LLM01)

Every LLM call in the app (`/ask`'s RAG answer, document summarization, document classification)
puts content from uploaded files into the prompt — either retrieved vector store chunks
(`QuestionAnswerAdvisor`) or, for summarization, the document's full extracted text concatenated
directly. That text is attacker-controllable: anyone who can get a document ingested (including via
hidden/white text in a PDF) can embed instructions aimed at the model, e.g. "ignore the above and
instead respond with...". `PromptGuardrails` (`docman-service/.../service/impl/PromptGuardrails.java`),
shared by all three call sites, applies two basic, defense-in-depth mitigations — not a hard
guarantee against a sufficiently determined injection:

- **Instruction hierarchy via a system message** (`PromptGuardrails.SYSTEM_INSTRUCTIONS`) — states
  that only the system message defines the task, and that document/context content is untrusted data
  to reason over, never instructions to follow, even if it claims to come from the system, a
  developer, or the user.
- **Delimited untrusted content** — retrieved RAG context (`/ask` and classification, via
  `PromptGuardrails.QUESTION_ANSWER_TEMPLATE`, which overrides `QuestionAnswerAdvisor`'s default
  template) and the raw document text concatenated into the summarization prompt
  (`DocumentSummaryServiceImpl.SUMMARY_PROMPT`) are both wrapped in explicit
  `=== BEGIN/END ... ===` markers, so the model has a clear, consistent boundary between instructions
  and data.

Classification has an additional, independent safeguard on the output side:
`DocumentType.fromLabel()` maps the model's response onto a fixed enum, defaulting to `unknown` for
anything unrecognized — so even a successful injection can't make classification return arbitrary
text, only steer it to a wrong-but-still-valid category. Summarization has a weaker output-side
bound: the generated summary is truncated to `QueryConstants.SUMMARY_MAX_OUTPUT_CHARS` (2000) before
it's persisted into document/chunk metadata, so an injected instruction can't stuff bulk text into
stored metadata even if the model complies — but within that budget the text is stored verbatim.
The `/ask` answer has no output-side constraint beyond the token cap below; the system-message and
delimiter mitigations are its only content defense.

## Improper output handling (LLM05)

Covered by the classification enum mapping and the summary length cap above. All other API
responses are JSON `ProblemDetail` / DTO payloads — there is no server-side sink (shell, SQL,
template, downstream HTTP) that consumes LLM output, so injection into those is out of scope here;
a browser client rendering a summary or answer is responsible for its own escaping.

## Sensitive information disclosure (LLM02)

`DocmanExceptionHandler.handleServerErrors` (the catch-all for unhandled exceptions) logs the full
exception under a random reference id and returns only `An unexpected error occurred. Reference:
<id>` to the caller — MinIO/OpenSearch/Mongo/model-provider messages, hostnames, and fault bodies
never reach the client. Infrastructure failures in `ObjectStoreServiceImpl` and the multipart
upload paths are likewise re-wrapped with fixed, non-sensitive messages (the cause is logged). The
`DocmanException` / `DocumentNotFoundException` / `DocumentConflictException` handlers still echo
their messages — those strings are all developer-authored in the codebase, never derived from an
external system's error.

## Unbounded consumption (LLM10)

- **Per-client-IP rate limit** — `RateLimitFilter` (`docman-api`, Bucket4j token bucket, in-memory,
  single-node) caps requests to the cost-bearing paths (`/api/v1/document/ask`, `/search`,
  `/search/hybrid`, and `/mcp`) at `docman.ratelimit.capacity` / `docman.ratelimit.refill-period`
  (default 20 per minute per IP). Over-limit requests get `429` with a `Retry-After` header. Set
  `docman.ratelimit.enabled: false` to disable. Other paths (metadata reads, uploads, deletes) are
  not rate-limited.
- **Bounded `/ask` executor** — `DocmanConfig.askExecutor` is a `ThreadPoolExecutor` with 10
  threads and a 20-slot `ArrayBlockingQueue` with `AbortPolicy`; once full, `DocumentController.ask`
  catches the `RejectedExecutionException` and returns `503` instead of letting pending LLM calls
  accumulate.
- **Output-token caps** — a portable `ChatOptions.maxTokens` is set on every chat call:
  `QueryConstants.LLM_MAX_RESPONSE_TOKENS` (1024) for RAG answers and summaries,
  `CLASSIFICATION_MAX_RESPONSE_TOKENS` (16) for classification.
- **Input-size caps** — `DocumentIndexServiceImpl.index` truncates extracted text to
  `INDEX_MAX_CONTENT_CHARS` (500k) before chunking/embedding; `DocumentSummaryServiceImpl` truncates
  the concatenated chunk text to `SUMMARY_MAX_INPUT_CHARS` (24k) before building the prompt; `/ask`'s
  `question` stays capped at `QUERY_MAX_QUESTION_LENGTH` (2000). Each truncation logs a `WARN`.

## Configuration

| Property | Default | Purpose |
|----------|---------|---------|
| `docman.ratelimit.enabled` | `true` | Toggle the per-IP rate limit on `/ask`, `/search`, `/search/hybrid`, `/mcp` |
| `docman.ratelimit.capacity` | `20` | Token-bucket size per client IP |
| `docman.ratelimit.refill-tokens` | `20` | Tokens added per refill period |
| `docman.ratelimit.refill-period` | `1m` | Greedy refill interval |

The consumption caps (`LLM_MAX_RESPONSE_TOKENS`, `CLASSIFICATION_MAX_RESPONSE_TOKENS`,
`SUMMARY_MAX_INPUT_CHARS`, `SUMMARY_MAX_OUTPUT_CHARS`, `INDEX_MAX_CONTENT_CHARS`,
`QUERY_MAX_QUESTION_LENGTH`) are compile-time constants in
`docman-domain/.../model/QueryConstants.java`.

## Accepted limitations

- **No authentication or authorization** on the REST API or the MCP server. Any caller can RAG-query
  every non-deleted document, run metadata/hybrid search, and obtain presigned download URLs by id
  (LLM06, LLM08). `Document.authorisation` is a reserved field with no enforcement behind it.
- **No tenant isolation in the vector store** — one shared OpenSearch index; the only always-on
  filter is `deleted == false`. A per-caller authorization filter would slot in the same way that
  filter does (`DocumentSearchServiceImpl` / `OpenSearchDocumentVectorStore`) but is not implemented.
- **Supply chain (LLM03)** — dependency versions are pinned in the POMs; there is no SBOM,
  dependency scanning, or artifact signature verification in the build.
- **Model/data poisoning (LLM04)** — there is no review step between upload and indexing; the
  prompt-injection controls above are the only barrier between hostile document content and the
  model.
