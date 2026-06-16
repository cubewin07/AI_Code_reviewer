# AI Code Reviewer — Architecture

## Overview

A self-hosted GitHub App that automatically reviews every **push** and **pull request** across any installed repository.
When a webhook arrives the server validates the signature, enqueues a review job, and a background worker runs a **ReAct agent** that gathers rich context from GitHub and then calls an LLM (via **9Router**) to produce a detailed Markdown review posted back as a PR/commit comment.

---

## Component Diagram

```
GitHub ──webhook──▶ [Caddy TLS termination]
                            │
                            ▼
              ┌─────────────────────────────┐
              │   Spring Boot Monolith      │
              │                             │
              │  ┌──────────────────────┐   │
              │  │  Webhook Controller  │   │  ← validates HMAC-SHA256 signature
              │  └────────┬─────────────┘   │
              │           │ enqueue          │
              │  ┌────────▼─────────────┐   │
              │  │  SQLite Job Queue    │   │  ← jobs table (status, payload, retries)
              │  └────────┬─────────────┘   │
              │           │ poll/process     │
              │  ┌────────▼─────────────┐   │
              │  │  Background Worker   │   │  ← Spring @Scheduled / virtual threads
              │  └────────┬─────────────┘   │
              │           │                 │
              │  ┌────────▼─────────────┐   │
              │  │   ReAct Agent        │   │
              │  │  ┌───────────────┐   │   │
              │  │  │ GitHub Tools  │   │   │  ← diff, file content, PR meta, README
              │  │  │ Think / Act   │   │   │
              │  │  │ LLM (9Router) │   │   │  ← Nvidia NIM / Ollama models
              │  │  └───────────────┘   │   │
              │  └────────┬─────────────┘   │
              │           │ POST review     │
              └───────────┼─────────────────┘
                          │
                          ▼
                   GitHub API  (PR comment / commit comment)
```

---

## Technology Choices

| Concern | Choice | Reason |
|---|---|---|
| Language / Runtime | Java 21 + Spring Boot 3.x | User preference |
| Web layer | Spring MVC (REST) | Simple, well-known |
| Async processing | Spring `@Scheduled` + virtual threads (JDK 21) | No extra infra |
| Job persistence | SQLite via JDBC (`sqlite-jdbc`) | Zero-ops, file-based |
| ORM / SQL | JDBI 3 or Spring JDBC Template | Lightweight |
| LLM gateway | 9Router (OpenAI-compatible REST) | Single abstraction over Nvidia/Ollama |
| GitHub integration | GitHub App (JWT auth + Installation tokens) | Webhook + API access |
| TLS termination | Caddy (on VM) | Handled externally |
| Observability | SLF4J + Logback, Actuator metrics | Standard Spring stack |

---

## GitHub App Permissions Required

| Permission | Level | Purpose |
|---|---|---|
| `contents` | Read | Read file contents, diffs |
| `pull_requests` | Write | Post PR comments |
| `commit_statuses` | Write | Optional: set status checks |
| `metadata` | Read | Repo metadata |

### Webhook Events Subscribed
- `push`
- `pull_request` (opened, synchronize, reopened)

---

## ReAct Agent — Tool Set

The agent follows a **Thought → Action → Observation** loop. Each iteration picks one tool:

| Tool | API Call | Purpose |
|---|---|---|
| `get_pr_metadata` | `GET /repos/{owner}/{repo}/pulls/{number}` | PR title, description, labels, base/head SHA |
| `get_commit_info` | `GET /repos/{owner}/{repo}/commits/{sha}` | Commit message, author |
| `get_diff` | `GET /repos/{owner}/{repo}/pulls/{number}/files` or commit comparison | Changed files + patch |
| `get_file_content` | `GET /repos/{owner}/{repo}/contents/{path}?ref={sha}` | Full file source |
| `get_readme` | `GET /repos/{owner}/{repo}/readme` | High-level repo context |
| `finish` | — | Emit the final Markdown review |

The agent is **token-budget aware**: it truncates file content and limits context window to the model's maximum.

---

## Job Lifecycle

```
PENDING → IN_PROGRESS → DONE
                      ↘ FAILED (retried up to N times → DEAD_LETTER)
```

SQLite `jobs` table columns:
- `id`, `event_type`, `repo_full_name`, `payload` (JSON), `status`, `attempts`, `created_at`, `updated_at`, `error`

---

## Security

- **Webhook signature**: `X-Hub-Signature-256` HMAC-SHA256 validated before any processing.
- **GitHub App JWT**: Short-lived JWTs signed with the private key; exchanged for installation access tokens.
- **Secrets**: Loaded from environment variables / application secrets (never committed).
- **Rate limiting**: Respects GitHub API secondary rate limits; backs off on 429.
