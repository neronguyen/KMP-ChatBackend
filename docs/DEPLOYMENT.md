# Free-Tier Production Deployment Guide

This guide covers deploying `KMP-ChatBackend` and all backing services (`PostgreSQL 17`, `Redis 7`, `RabbitMQ 4`, and `Caddy` auto-TLS reverse proxy) on **$0/month free-tier infrastructure**.

---

## Architecture Overview

```text
Android Client (HTTPS / WSS)
        │
        ▼ :443 (Let's Encrypt TLS via Caddy)
┌───────────────────────────────────────────────┐
│ Docker Bridge Network (nerochat_internal)     │
│                                               │
│  Caddy (:80/:443) ── X-Real-IP ──▶ Backend (:8080)
│                                      ├──▶ PostgreSQL 17
│                                      ├──▶ Redis 7 (Redisson)
│                                      └──▶ RabbitMQ 4
└───────────────────────────────────────────────┘
        │
        ▼ :587 STARTTLS
  Brevo / Resend SMTP (OTP Delivery)
```

---

## Option A — OCI Always Free ARM (Primary, Recommended)

Oracle Cloud Infrastructure's Always Free Ampere A1 tier provides up to **4 OCPUs and 24 GB RAM** for \$0/month—more than enough to co-locate the JVM backend and all backing services in a single `docker compose` stack.

### 1. Provision & Harden VM
1. Create an **Ubuntu 24.04 (aarch64)** VM (`VM.Standard.A1.Flex`, e.g., 2 OCPUs / 12 GB RAM).
2. In the OCI **VCN Security List** (and Ubuntu `ufw` / `iptables`), allow **only** inbound TCP `22` (SSH), `80` (ACME HTTP-01), and `443` (HTTPS/WSS). Never expose `5432`, `6379`, `5672`, or `8080` publicly.
3. Install Docker Engine + Docker Compose plugin:
   ```bash
   curl -fsSL https://get.docker.com | sh
   sudo usermod -aG docker "$USER"
   ```

### 2. Configure DNS & Secrets
1. Point a free domain or DuckDNS subdomain (`<name>.duckdns.org`) `A` record to the VM's public IP.
2. Clone `KMP-ChatBackend` and create `.env`:
   ```bash
   git clone https://github.com/neronguyen/KMP-ChatBackend.git
   cd KMP-ChatBackend
   cp .env.example .env
   ```
3. Fill in `.env`:
   - Set `DOMAIN=<your-domain>`
   - Set `GITHUB_ACTOR` and `GITHUB_TOKEN` (classic PAT with `read:packages` to pull `KMP-ChatContract`)
   - Generate `JWT_SECRET_BASE64` (`openssl rand -base64 64 | tr -d '\n'`) and `OTP_PEPPER` (`openssl rand -hex 32`)
   - Configure **Brevo** (300 emails/day free) or **Resend** SMTP credentials (`SMTP_HOST`, `SMTP_PORT=587`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_SENDER`).
   - On first boot before Flyway (`CHAT-10`) is added, keep `JPA_DDL_AUTO=update`; after the schema is created, switch it to `validate`.

### 3. Boot Stack
```bash
set -a && source .env && set +a
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml logs -f backend caddy
```
Caddy automatically provisions a Let's Encrypt certificate for `$DOMAIN` and proxies both `https://` and `wss://` to `backend:8080`.

---

## Option B — Hybrid OCI Micro / Render Fallbacks

If OCI ARM capacity is temporarily unavailable in your home region:

1. **OCI Always Free AMD Micro (1 GB RAM)** + Managed Free Tiers:
   - Run `caddy` + `backend` on the Micro VM (with `2 GB` swap), and offload DB/Cache/MQ to **Neon** (`PostgreSQL`), **Redis Cloud** (`30 MB` / `30 connections`), and **CloudAMQP Little Lemur** (`RabbitMQ`).
   - Set `REDIS_PROTOCOL=rediss` (or `redis` per provider), `REDIS_POOL_SIZE=5`, `REDIS_SUB_POOL_SIZE=5`, `RABBITMQ_PORT=5671`, and `RABBITMQ_SSL_ENABLED=true`.
2. **Render Web Service (Docker)** + Managed Free Tiers:
   - Render terminates TLS externally and injects `PORT` automatically (`server.port: ${PORT:8080}`). Note that Render's free web tier sleeps after 15 minutes of inactivity (~30–50s cold start).

---

## Post-Deploy Smoke Verification

1. **HTTPS & OpenAPI**:
   ```bash
   curl -i "https://$DOMAIN/v3/api-docs"
   ```
2. **Registration & Live OTP Email**:
   - Register a test user via `POST https://$DOMAIN/api/auth/register` and verify the 6-digit OTP arrives in your inbox.
3. **WebSocket (`wss://`)**:
   - Connect via `Android-ChatApp` (pointing `BASE_URL` / `WS_URL` at `https://$DOMAIN` / `wss://$DOMAIN`) or `websocat` with a valid Bearer JWT and verify real-time message delivery.
