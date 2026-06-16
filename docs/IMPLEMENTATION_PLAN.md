# AI Code Reviewer — Implementation Plan

## Phrase 0 — Project Skeleton & Foundations

> Goal: get a runnable Spring Boot JAR with all cross-cutting concerns in place before any feature code.

| # | Task | Description |
|---|---|---|
| 0.1 | **Init project** | Generate Spring Boot 3.x project (Maven) with dependencies: Web, Actuator, JDBC, Flyway, Lombok, Validation |
| 0.2 | **SQLite JDBC** | Add `sqlite-jdbc` + configure `DataSource` bean; disable Hibernate dialect auto-detection |
| 0.3 | **Flyway migrations** | Create `db/migration/V1__create_jobs_table.sql` with full jobs schema |
| 0.4 | **Config model** | `AppConfig` record loaded via `@ConfigurationProperties`; covers GitHub App, 9Router, server settings |
| 0.5 | **Logging** | Logback JSON appender for structured logs; MDC for `jobId`, `repo`, `eventType` |
| 0.6 | **Actuator & health** | Expose `/actuator/health` and `/actuator/metrics`; disable sensitive endpoints |
| 0.7 | **Dockerfile** | Multi-stage build (Eclipse Temurin 21-slim); copy SQLite data dir as volume |
| 0.8 | **Caddyfile skeleton** | Reverse proxy `https://review.yourdomain.com → localhost:8080` |

---

## Phrase 1 — GitHub App & Webhook Ingestion

> Goal: receive, validate, and persist GitHub webhooks reliably.

| # | Task | Description |
|---|---|---|
| 1.1 | **GitHub App registration** | Create GitHub App; note App ID, Client ID; generate & download private key (PEM) |
| 1.2 | **Webhook controller** | `POST /webhook` endpoint; read raw body **before** any parsing (required for HMAC) |
| 1.3 | **HMAC-SHA256 validation** | `WebhookSignatureValidator` service; constant-time compare; reject 401 on mismatch |
| 1.4 | **Event dispatching** | Parse `X-GitHub-Event` header; dispatch `push` and `pull_request` payloads to typed POJOs |
| 1.5 | **Job enqueue** | `JobRepository` saves a `PENDING` row to SQLite; controller returns `202 Accepted` immediately |
| 1.6 | **Idempotency** | Unique constraint on `(repo_full_name, event_type, delivery_id)`; ignore duplicate deliveries |
| 1.7 | **Integration test** | MockMvc test: valid signature → 202; invalid signature → 401; duplicate delivery → 200 no-op |

---

## Phrase 2 — GitHub API Client

> Goal: a typed, auth-aware GitHub REST client the agent can call as tools.

| # | Task | Description |
|---|---|---|
| 2.1 | **JWT generator** | Sign GitHub App JWT (RS256, 10-min expiry) using the PEM private key (`java.security` or Bouncy Castle) |
| 2.2 | **Installation token** | `POST /app/installations/{id}/access_tokens`; cache token until 1 min before expiry |
| 2.3 | **GitHub HTTP client** | Spring `RestClient` (or `WebClient`) wrapper; auto-injects `Authorization: token <tok>` |
| 2.4 | **Tool: get_pr_metadata** | Fetch PR JSON → typed `PullRequestMeta` record |
| 2.5 | **Tool: get_commit_info** | Fetch commit JSON → typed `CommitInfo` record |
| 2.6 | **Tool: get_diff** | Fetch PR files list (patches) or commit comparison; return `List<FileDiff>` |
| 2.7 | **Tool: get_file_content** | Fetch base64-encoded file content; decode; truncate to token budget |
| 2.8 | **Tool: get_readme** | Fetch repo README; truncate |
| 2.9 | **Rate limit guard** | Inspect `X-RateLimit-Remaining`; back off + retry on 429 / 403 secondary limit |
| 2.10 | **Unit tests** | WireMock stubs for each GitHub endpoint; test auth refresh flow |

---

## Phrase 3 — LLM Gateway (9Router)

> Goal: a thin, model-agnostic client that sends prompts to 9Router and parses responses.

| # | Task | Description |
|---|---|---|
| 3.1 | **9Router client** | `RestClient` POST to `9router-base-url/v1/chat/completions` (OpenAI-compatible schema) |
| 3.2 | **Config** | `9router.base-url`, `9router.api-key`, `9router.model`, `9router.max-tokens`, `9router.timeout-seconds` |
| 3.3 | **Prompt builder** | `PromptBuilder` assembles system prompt + agent scratchpad into `messages[]` array |
| 3.4 | **Response parser** | Extract `choices[0].message.content`; handle finish reason `stop` vs `length` |
| 3.5 | **Retry / fallback** | Exponential backoff on 5xx; optional fallback model name in config |
| 3.6 | **Token budget** | Count approximate tokens (char / 4 heuristic or tiktoken-style); trim context to stay within limit |
| 3.7 | **Integration test** | WireMock stub of 9Router; verify request schema and response parsing |

---

## Phrase 4 — ReAct Agent

> Goal: implement the Thought → Action → Observation loop that drives the code review.

| # | Task | Description |
|---|---|---|
| 4.1 | **Agent interface** | `ReviewAgent.review(JobContext) → ReviewResult` |
| 4.2 | **Tool registry** | `Map<String, AgentTool>` of all GitHub tools; tools are Spring beans |
| 4.3 | **ReAct loop** | Parse LLM output for `Action:` / `Action Input:` / `Final Answer:` markers; call tool; append observation; repeat |
| 4.4 | **System prompt** | Instruct model to act as a senior code reviewer; define tool names, expected output format |
| 4.5 | **Context assembly** | On first turn: inject event type, repo, PR/commit summary; tools fill in details lazily |
| 4.6 | **Max iterations guard** | Hard stop after N loops (configurable); emit partial review if exceeded |
| 4.7 | **Review formatter** | Convert `ReviewResult` to a Markdown string (severity buckets: 🔴 Error / 🟡 Warning / 🔵 Suggestion) |
| 4.8 | **Unit tests** | Stub LLM responses; verify tool calls and final Markdown output |

---

## Phrase 5 — Job Worker & Retry

> Goal: reliably process jobs from the SQLite queue with retries and dead-letter handling.

| # | Task | Description |
|---|---|---|
| 5.1 | **Worker scheduler** | `@Scheduled(fixedDelay)` bean; polls for `PENDING` or `FAILED` jobs with `attempts < max` |
| 5.2 | **Optimistic locking** | `UPDATE jobs SET status='IN_PROGRESS' WHERE id=? AND status='PENDING'` — prevents double-processing |
| 5.3 | **Job executor** | Deserialise payload → `JobContext`; call `ReviewAgent`; persist result |
| 5.4 | **Error handling** | Catch exceptions; increment `attempts`; set `FAILED`; after max attempts set `DEAD_LETTER` |
| 5.5 | **Concurrency** | `@Async` + virtual thread executor; configurable `worker.concurrency` (default 2) |
| 5.6 | **Job history** | Store `review_text`, completion timestamp in `jobs` table |
| 5.7 | **Integration test** | H2 in-memory (or SQLite file) + MockRestServiceServer; full job lifecycle test |

---

## Phrase 6 — GitHub Comment Posting

> Goal: post the agent's Markdown review back to GitHub.

| # | Task | Description |
|---|---|---|
| 6.1 | **PR comment poster** | `POST /repos/{owner}/{repo}/issues/{number}/comments` with `{ "body": "<markdown>" }` |
| 6.2 | **Push comment poster** | `POST /repos/{owner}/{repo}/commits/{sha}/comments` |
| 6.3 | **Review header** | Prepend a badge / header line: `🤖 AI Code Review — <model> via 9Router` |
| 6.4 | **Deduplication** | Check if a bot comment already exists for this SHA; edit rather than create a new one |
| 6.5 | **Error handling** | If comment POST fails, log error and mark job `FAILED` for retry |
| 6.6 | **Integration test** | WireMock stub; verify correct endpoint and body for PR vs push events |

---

## Phrase 7 — Hardening, Observability & Deployment

> Goal: production-ready service on the VM.

| # | Task | Description |
|---|---|---|
| 7.1 | **Metrics** | Micrometer counters: `jobs.enqueued`, `jobs.completed`, `jobs.failed`, `llm.latency` |
| 7.2 | **Structured logging** | Ensure every log line has `jobId`, `repo`, `eventType` in MDC |
| 7.3 | **Secrets management** | Document env vars; provide `.env.example`; never log secret values |
| 7.4 | **Graceful shutdown** | Spring `@PreDestroy` drains in-progress jobs before JVM exit |
| 7.5 | **Caddyfile final** | HTTPS, HSTS, rate limit on `/webhook` (e.g., 10 req/s per IP) |
| 7.6 | **systemd unit** | `ai-code-reviewer.service` with `Restart=always`, env file, working directory |
| 7.7 | **README** | Setup guide: GitHub App creation, env vars, build & deploy steps |
| 7.8 | **E2E smoke test** | Script that fires a mock webhook → checks SQLite job → checks GitHub API mock for comment |

---

## Phrase 8 — Enhancements (Post-MVP)

> Ideas to implement after the core loop is stable.

- **PR inline comments** — use GitHub Review API to post line-level comments on the diff
- **Configurable review policy** — per-repo YAML file (`.ai-reviewer.yml`) to tune severity, ignored paths
- **Multiple models** — A/B different 9Router models; compare review quality
- **Web dashboard** — simple Spring MVC HTML page to browse job history and reviews
- **Webhook replay** — admin endpoint to re-process a past delivery ID
- **Slack / email notifications** — alert on `DEAD_LETTER` jobs
