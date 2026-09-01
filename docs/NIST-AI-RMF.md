# NIST AI RMF — Generative AI Profile

This document positions Docman AI against the
[NIST AI Risk Management Framework (AI 100-1)](https://www.nist.gov/itl/ai-risk-management-framework)
and its
[Generative AI Profile (NIST AI 600-1, July 2024)](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf).

It is a companion to [`docs/AI-SECURITY.md`](AI-SECURITY.md): that document maps the
[OWASP Top 10 for LLM Applications](https://genai.owasp.org/llm-top-10/) to concrete controls in
the codebase; this one places those same controls (and the gaps around them) inside the RMF's
**Govern / Map / Measure / Manage** structure and the twelve generative-AI risks called out in AI
600-1.

## Scope and honesty caveat

Docman AI is a **reference implementation**, not a governed production deployment. The RMF is
written for the organisation that operates an AI system, and many of its subcategories — workforce
training, legal review, organisational risk tolerance, third-party contracts — have no meaningful
answer at the level of a sample codebase. Those are marked **Adopter responsibility** below: the
project can show *where* a control would attach, but the control itself only exists once a real
operator stands the system up.

Status values used in this document:

| Status | Meaning |
|--------|---------|
| **Implemented** | A concrete, code-level control exists and is described here with a pointer to it |
| **Partial** | Some mitigation exists; known weaknesses remain, documented inline |
| **Adopter responsibility** | Organisational or deployment-time control the reference implementation cannot embody |
| **Not applicable** | The risk does not arise given what this system does |

## Generative AI risks (NIST AI 600-1)

AI 600-1 enumerates twelve risks that are new to, or amplified by, generative AI. Applicability to
Docman AI — a retrieval-augmented Q&A / summarisation / classification system over user-uploaded
documents, with a read-only MCP tool surface:

| # | GAI risk | Applicability | Status | Where addressed |
|---|----------|---------------|--------|-----------------|
| 1 | CBRN information or capabilities | Low — no uplift beyond the base model; RAG is scoped to the user's own uploaded corpus | Adopter responsibility | Model-provider acceptable-use terms (OpenAI / self-hosted) |
| 2 | Confabulation ("hallucination") | **High** — the core output is a generated answer/summary | Partial | Grounded RAG prompt + delimiters ([`AI-SECURITY.md` LLM09](AI-SECURITY.md#summary)); summary skipped when no chunks retrieved; classification constrained to an enum. No automated faithfulness scoring yet — see [Measure](#measure) |
| 3 | Dangerous, violent, or hateful content | Low — outputs are grounded in the user's own documents | Adopter responsibility | Provider-side moderation; `spring.ai.model.moderation` is deliberately `none` (see [`SETUP.md`](SETUP.md#troubleshooting)) and would be the attach point |
| 4 | Data privacy | **High** — documents may contain PII/PHI/confidential data | Partial | Content stays within the operator's own MinIO / OpenSearch / model provider; error responses never leak infra detail ([`AI-SECURITY.md` LLM02](AI-SECURITY.md#sensitive-information-disclosure-llm02)). **No authN/authz, no tenant isolation** — see [accepted limitations](#accepted-limitations) |
| 5 | Environmental impacts | Low | Partial | Input-size and output-token caps (`QueryConstants`) bound compute per request; self-hosted Ollama option avoids third-party inference |
| 6 | Harmful bias / homogenisation | Low–Medium — classification could systematically mis-file certain document classes | Partial | `temperature 0` for classification gives determinism, not fairness; no bias evaluation performed |
| 7 | Human-AI configuration | Medium — an agent calls tools via MCP; humans read AI summaries as if authoritative | Implemented | MCP surface is **read-only by design** (no create/update/delete/restore — [`ARCHITECTURE.md` MCP Server](ARCHITECTURE.md#mcp-server)); every AI field (`summary`, `documentType`) is stored distinctly from user-supplied metadata and is traceable to its Kafka lifecycle event |
| 8 | Information integrity | **High** — a poisoned upload can steer answers | Partial | Prompt-injection mitigations (`PromptGuardrails`) on every LLM call; `deleted == false` force-filtered on all retrieval. No upload review/quarantine step — see [`AI-SECURITY.md` LLM04](AI-SECURITY.md#accepted-limitations) |
| 9 | Information security | **High** | Implemented / Partial | Full OWASP LLM Top 10 review in [`AI-SECURITY.md`](AI-SECURITY.md): rate limiting, bounded executor, token and input-size caps, generic error handling. Accepted gaps: no authN/authz, no SBOM |
| 10 | Intellectual property | Medium — uploaded content may be third-party copyrighted; model may reproduce it | Adopter responsibility | Governed by the operator's data-ingestion policy and model-provider terms |
| 11 | Obscene, degrading, or abusive content | Low | Adopter responsibility | Same attach point as risk 3 (provider moderation) |
| 12 | Value chain & component integration | **High** — Spring AI, model providers, OpenSearch, Tika, Temporal, Kafka | Partial | Dependency versions pinned in the Maven POMs; provider abstraction keeps swap cost low. No SBOM, dependency scanning, or artifact signature verification in the build ([`AI-SECURITY.md` LLM03](AI-SECURITY.md#accepted-limitations)) |

## Govern

Cross-cutting function: policies, roles, accountability, culture. Almost entirely **adopter
responsibility** — recorded here so an adopter knows what to put in place.

| RMF subcategory (abbrev.) | Status | Notes for this codebase / an adopter |
|---|---|---|
| GOVERN 1.1 — legal & regulatory requirements understood | Adopter responsibility | Depends on data domain (GDPR / HIPAA / sector rules) and where inference runs. The `prod` profile sends content to OpenAI; the default profile keeps it on-prem via Ollama — a jurisdiction-relevant choice |
| GOVERN 1.2 — trustworthy-AI characteristics in policy | Adopter responsibility | This project documents *technical* posture (`AI-SECURITY.md`, this file); an operator still needs an AI-use policy referencing them |
| GOVERN 1.3 — risk tolerance defined | Adopter responsibility | The consumption caps in `QueryConstants` and `docman.ratelimit.*` are the *knobs*; the *values* an operator accepts are a risk-tolerance decision |
| GOVERN 1.4 — risk-management process | Partial | This document + `AI-SECURITY.md` are the risk register for the reference implementation. An adopter should fold them into their own process |
| GOVERN 1.5 — monitoring & periodic review | Adopter responsibility | No telemetry/metrics are emitted today (see [Measure](#measure)); the Kafka `documents` topic is the hook for build-out |
| GOVERN 1.6 — inventory of AI systems | Adopter responsibility | One system, two model configurations (default Ollama, `prod` OpenAI) — the inventory entry should list both |
| GOVERN 2.1 — roles & responsibilities | Adopter responsibility | — |
| GOVERN 3.2 — human oversight defined | Implemented (design) | AI outputs are advisory and non-destructive: `summary`/`documentType` never gate access or delete data; the MCP tool surface excludes all mutation |
| GOVERN 4.1 — culture of responsible AI / critical thinking | Adopter responsibility | — |
| GOVERN 5 — feedback from external parties | Adopter responsibility | — |
| GOVERN 6.1 / 6.2 — third-party / value-chain risk | Partial | Provider-agnostic abstraction (`ChatClient` / `EmbeddingModel` / `VectorStore` / `DocumentVectorStore`) limits lock-in and eases swapping a provider found deficient. Contractual / SBOM controls are the adopter's |

**Concrete Govern artefacts an adopter should add:** an AI-use policy; a completed AI system impact
assessment (see [ISO/IEC 42005] or the RMF's own guidance); named accountable owner; a scheduled
review cadence for prompts, model versions, and this document.

## Map

Establish context and identify risks for the specific system.

| RMF subcategory (abbrev.) | Status | Notes |
|---|---|---|
| MAP 1.1 — intended purpose, context, assumptions documented | Implemented | [`CLAUDE.md`](../CLAUDE.md), [`README.md`](../README.md), [`ARCHITECTURE.md`](ARCHITECTURE.md) describe the system end to end |
| MAP 1.2 — interdisciplinary perspectives | Adopter responsibility | — |
| MAP 2.1 — task & method defined (incl. why generative AI) | Implemented | RAG over the user's own corpus for Q&A; retrieval-grounded summary; enum-constrained classification. Each is scoped to a single document's own chunks except `/ask`, which spans the (non-deleted) corpus — see [`ARCHITECTURE.md`](ARCHITECTURE.md#search--rag) |
| MAP 2.2 — system knowledge & limitations documented; output expectations | Implemented | `DocumentStatus` lifecycle, the "summary skipped when no chunks" and "classification falls back to `unknown`" behaviours, and the eventual-consistency windows are all documented in [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| MAP 2.3 — scientific integrity / TEVV for the method | Partial | Design rationale is documented (e.g. RRF constant, temperature choice); no formal test/eval suite for output quality yet |
| MAP 3.x — benefits & costs, incl. non-AI alternatives | Implemented (partial) | Hybrid search deliberately keeps a non-AI (BM25) retrieval leg; `/search` is pure lexical metadata search with no model call |
| MAP 4.x — third-party components & IP risk mapped | Partial | Value-chain components are enumerated in [`ARCHITECTURE.md`](ARCHITECTURE.md#tech-stack); IP posture of ingested content is adopter responsibility |
| MAP 5.1 — likelihood & magnitude of each impact | Partial | The [GAI risk table](#generative-ai-risks-nist-ai-600-1) above is the first-pass impact map; per-deployment magnitude depends on data sensitivity |
| MAP 5.2 — practices for affected-community feedback | Adopter responsibility | — |

## Measure

Analyse, assess, benchmark, and monitor risks. **This is the weakest function today** and the
highest-value place to invest.

| RMF subcategory (abbrev.) | Status | Notes |
|---|---|---|
| MEASURE 1.1 — approaches & metrics selected | Not implemented | No evaluation metrics are defined or collected. Recommended: RAG faithfulness / context-precision / answer-relevance (RAGAS-style), classification accuracy against a labelled set, injection-resistance red-team pass rate |
| MEASURE 1.3 — independent review of measurement | Adopter responsibility | — |
| MEASURE 2.2 — human-subject / UX evaluation | Adopter responsibility | — |
| MEASURE 2.3 — system performance vs. deployment conditions | Partial | Load behaviour of the concurrency guard and rate limiter has been exercised manually (see [`ARCHITECTURE.md`](ARCHITECTURE.md#document-versioning--revision-history)); model-output quality has not |
| MEASURE 2.5 — validity & reliability | Partial | `temperature 0` gives classification *reproducibility*; no accuracy measurement |
| MEASURE 2.6 — safety / failure-mode evaluation | Partial | Failure modes are enumerated and handled in the workflow (a failed summary/classification never fails the document); not adversarially tested |
| MEASURE 2.7 — security & resilience (red-team) | Partial | Prompt-injection mitigations exist ([`AI-SECURITY.md` LLM01](AI-SECURITY.md#prompt-injection-mitigations-llm01)) but are explicitly "defense-in-depth, not a guarantee" and have not been red-teamed |
| MEASURE 2.8 — transparency & accountability of the model | Partial | Provider/model in use is config-visible; no model card is committed |
| MEASURE 2.9 — model explainability | Partial | RAG answers are grounded in retrievable chunks; `/ask` does not currently return the source chunk IDs to the caller (the MCP `askQuestion` tool and REST `/ask` return prose only) |
| MEASURE 2.10 — privacy evaluation | Not implemented | No PII detection / redaction on ingest or in outputs |
| MEASURE 2.11 — bias evaluation | Not implemented | — |
| MEASURE 2.13 — monitoring in production | Not implemented | No metrics, traces, or per-call audit log of prompt / retrieved-context / token-usage / caller |
| MEASURE 3.x — tracking identified & emergent risks | Partial | This document + `AI-SECURITY.md` are the tracking record; there is no automated regression signal |
| MEASURE 4.x — feedback loops on measurement efficacy | Adopter responsibility | — |

**Recommended Measure build-out, in priority order:**

1. **Per-call audit log** — for every chat call record: timestamp, caller/IP, endpoint or MCP tool,
   question hash, retrieved chunk IDs, model + provider, prompt/response token counts, latency,
   outcome. This is the backbone every downstream measurement and every RMF *Manage* incident
   response needs, and it does not exist yet. Natural home: an advisor around the `ChatClient` plus
   a listener on the existing Kafka `documents` topic.
2. **RAG evaluation suite in CI** — a committed golden set of documents + questions + expected
   answers/citations, scored for faithfulness and context precision on each build. Turns
   [GAI risk 2 (confabulation)](#generative-ai-risks-nist-ai-600-1) from a claim into a number.
3. **Classification accuracy set** — a labelled document sample checked against `DocumentType`
   output; catches drift when the model version or prompt changes.
4. **Injection-resistance test** — a corpus of documents carrying known injection payloads; assert
   the summary/answer/classification is not steered. Regression-guards `PromptGuardrails`.
5. **Token / cost & rate-limit metrics** — export counters so `docman.ratelimit.*` and the
   `askExecutor` bounds can be tuned against real traffic (GAI risk 5, OWASP LLM10).

## Manage

Allocate resources to mapped and measured risks; respond, recover, communicate.

| RMF subcategory (abbrev.) | Status | Notes |
|---|---|---|
| MANAGE 1.x — risks prioritised & resources allocated | Partial | Prioritisation is captured in `AI-SECURITY.md` ("Mitigated" vs. "Accepted limitations") and this document's status columns |
| MANAGE 2.1 — mechanisms to sustain value / deactivate | Implemented (partial) | `docman.ratelimit.enabled: false` and the model-provider config are the runtime controls; a full kill switch (disable `/ask` + `/mcp`) would be a small addition |
| MANAGE 2.2 — mechanisms for unexpected-risk response | Partial | Soft delete (`DELETE /document/{id}/soft`) removes a document from all AI retrieval paths without destroying it — the primary lever for pulling problematic content out of RAG. Propagation is eventually-consistent (see [`ARCHITECTURE.md`](ARCHITECTURE.md#soft-delete)) |
| MANAGE 2.3 / 2.4 — superseded / problematic models retired, incidents communicated | Adopter responsibility | Provider abstraction makes swapping a model mechanically cheap; the *decision* and *comms* are the operator's |
| MANAGE 3.x — third-party risk managed & monitored | Partial | Same as GOVERN 6 — abstraction limits blast radius; monitoring is the adopter's |
| MANAGE 4.1 — post-deployment monitoring plan | Not implemented | Depends on the Measure audit-log/metrics build-out above |
| MANAGE 4.2 — continual improvement | Partial | This document and `AI-SECURITY.md` are versioned with the code and updated as controls change |
| MANAGE 4.3 — incident reporting & information sharing | Adopter responsibility | — |

**Incident-response levers that exist today:**

- **Remove content from RAG:** `DELETE /api/v1/document/{id}/soft` (reversible via
  `POST /api/v1/document/{id}/restore`).
- **Throttle or stop abuse:** tighten `docman.ratelimit.capacity` / `refill-period`, or block the
  cost-bearing paths (`/ask`, `/search`, `/search/hybrid`, `/mcp`) at the ingress.
- **Swap the model/provider:** change `spring.ai.model.chat` / `spring.ai.model.embedding` (and the
  index for a dimensionality change) — no code change.
- **Contain a bad summary/classification:** both are stored as ordinary metadata and can be
  overwritten with `PUT /api/v1/document/{id}`; neither has any enforcement effect.

## Accepted limitations

Carried over from [`docs/AI-SECURITY.md`](AI-SECURITY.md#accepted-limitations) because they are
also RMF gaps (chiefly under *Govern*, *Map 5*, and *Measure 2.10*):

- **No authentication or authorisation** on the REST API or the MCP server. Any caller can
  RAG-query every non-deleted document, run search, and mint presigned download URLs by ID.
  (GAI risks 4, 9; MANAGE / GOVERN 3.)
- **No tenant isolation in the vector store** — one shared OpenSearch index; the only always-on
  filter is `deleted == false`. A per-caller authorisation filter would attach exactly where that
  one does (`DocumentSearchServiceImpl` / `OpenSearchDocumentVectorStore`).
- **No production telemetry** — no metrics, tracing, or per-call audit log. This blocks most of the
  *Measure* and *Manage 4* functions until built (see [Measure build-out](#measure)).
- **No output-quality evaluation** — faithfulness, classification accuracy, and bias are
  unmeasured.
- **No PII detection/redaction** on ingest or in generated output.
- **Supply chain** — versions pinned, but no SBOM, dependency scanning, or signature verification
  in the build.
- **No content moderation** — `spring.ai.model.moderation` is `none`; provider-side moderation (if
  any) is the only filter on dangerous/abusive content.

## References

- [NIST AI 100-1 — AI Risk Management Framework 1.0](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.100-1.pdf)
- [NIST AI 600-1 — Generative AI Profile](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf)
- [OWASP Top 10 for LLM Applications (2025)](https://genai.owasp.org/llm-top-10/) — see
  [`docs/AI-SECURITY.md`](AI-SECURITY.md)
- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — system design the controls above attach to
- [ISO/IEC 42001] AI management systems, [ISO/IEC 23894] AI risk management, [ISO/IEC 42005] AI
  system impact assessment — complementary standards an adopter may certify against

[ISO/IEC 42001]: https://www.iso.org/standard/81230.html
[ISO/IEC 23894]: https://www.iso.org/standard/77304.html
[ISO/IEC 42005]: https://www.iso.org/standard/44545.html
</content>
</invoke>
