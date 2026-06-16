# AI Code Reviewer

A self-hosted GitHub App that automatically reviews every **push** and **pull request** across any repository where it is installed.
A **ReAct agent** gathers rich context (diff, file contents, PR metadata, README) and posts a Markdown review comment powered by an open LLM via **9Router**.

---

## Tech Stack

| Layer | Choice |
|---|---|
| Runtime | Java 21 + Spring Boot 3.x (Maven) |
| Database | SQLite (file-based, zero-ops) |
| Migrations | Flyway |
| LLM gateway | 9Router (OpenAI-compatible — Nvidia NIM / Ollama) |
| GitHub integration | GitHub App (JWT + installation tokens) |
| TLS | Caddy (on VM) |
| Deployment | systemd + Docker |

---

## High-level Architecture

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the component diagram and detailed design notes.
See [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) for the full phase-by-phase implementation plan.

---

## Setup (Quick Reference)

> Full guide coming in Phrase 7.

1. **Create a GitHub App** — set webhook URL to `https://review.yourdomain.com/webhook`; subscribe to `push` and `pull_request` events.
2. **Copy `.env.example` → `.env`** and fill in secrets (App ID, PEM key path, Webhook secret, 9Router credentials).
3. **Build**: `mvn package -DskipTests`
4. **Run**: `java -jar target/ai-code-reviewer.jar`
5. **Deploy**: use the provided `systemd` unit and `Caddyfile`.

---

## Implementation Phases

| Phrase | Goal |
|---|---|
| 0 | Project skeleton, SQLite, Flyway, config, Docker, Caddy |
| 1 | Webhook ingestion, HMAC validation, job queue |
| 2 | GitHub App auth, REST client, all ReAct tools |
| 3 | 9Router LLM gateway |
| 4 | ReAct agent loop |
| 5 | Background job worker & retry |
| 6 | GitHub comment posting |
| 7 | Hardening, observability, deployment |
| 8 | Post-MVP enhancements |

---

## License

MIT
