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

## Setup & Deployment Guide

### 1. GitHub App Configuration
1. Go to your GitHub profile or organization settings -> **Developer settings** -> **GitHub Apps** -> **New GitHub App**.
2. Set the following fields:
   - **GitHub App name**: `Your App Name`
   - **Homepage URL**: `https://yourdomain.com`
   - **Webhook**: Check **Active**
   - **Webhook URL**: `https://yourdomain.com/webhook`
   - **Webhook secret**: A secure random string (save this for `.env`)
3. Under **Permissions & events**, set:
   - **Repository permissions**:
     - `Contents`: **Read-only** (to read code files and diffs)
     - `Metadata`: **Read-only** (default, for repository information)
     - `Pull requests`: **Read & write** (to post reviews as PR comments)
     - `Commit statuses`: **Read & write** (optional, if you want status checks)
   - **Subscribe to events**:
     - `Push`
     - `Pull request`
4. Save the app, then click **Generate a private key** at the bottom. Download the `.pem` private key file.

### 2. Local Environment Setup
1. Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
2. Open `.env` and configure the following variables:
   - `GITHUB_APP_ID`: Your App ID from the GitHub App General page.
   - `GITHUB_PRIVATE_KEY_PATH`: Absolute path to the downloaded `.pem` key.
   - `GITHUB_WEBHOOK_SECRET`: The webhook secret you entered.
   - `NINE_ROUTER_BASE_URL`: Base URL for the 9Router gateway.
   - `NINE_ROUTER_API_KEY`: Your 9Router API token.

### 3. Build
Build the executable Spring Boot JAR using Gradle:
```bash
./gradlew bootJar
```
This produces the JAR file at `build/libs/ai-code-reviewer-0.0.1-SNAPSHOT.jar`.

### 4. systemd Deployment
1. Create directory `/opt/ai-code-reviewer` and copy the built JAR there:
   ```bash
   sudo mkdir -p /opt/ai-code-reviewer
   sudo cp build/libs/ai-code-reviewer-0.0.1-SNAPSHOT.jar /opt/ai-code-reviewer/ai-code-reviewer.jar
   sudo cp .env /opt/ai-code-reviewer/.env
   ```
2. Copy `ai-code-reviewer.service` to systemd:
   ```bash
   sudo cp ai-code-reviewer.service /etc/systemd/system/
   ```
3. Enable and start the service:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now ai-code-reviewer
   ```

### 5. Reverse Proxy (Caddy)
Copy the configuration from the provided `Caddyfile` into `/etc/caddy/Caddyfile` and reload Caddy:
```bash
sudo systemctl reload caddy
```

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
