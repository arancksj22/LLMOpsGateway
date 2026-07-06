# LLM Gateway — Complete Usage Guide

Everything you need to run, demo, test, and measure the gateway. All commands
are run from the repo root unless noted. Windows-friendly (PowerShell) — the
`curl` examples work in Git Bash / WSL; PowerShell equivalents are noted where
syntax differs.

---

## 1. Prerequisites

| Tool | Needed for | Notes |
|---|---|---|
| Docker Desktop | the cluster (required) | must be running before `docker compose` |
| Python 3 | `scripts/*.py` | stdlib only, no pip installs |
| Java 21 + Maven | only for running the backend *outside* Docker | `backend/mvnw` wrapper included |
| k6 | load testing | optional locally — a Docker fallback is given below |

---

## 2. API keys — what goes where

There are **three different kinds of keys**. Don't mix them up:

### 2.1 Provider keys (optional, go in `.env`)

Real upstream LLM keys. Stored **server-side only** — client apps never see them.

| Key | Where to get it (free) | `.env` variable |
|---|---|---|
| Groq | https://console.groq.com → API Keys | `GROQ_API_KEY` |
| Gemini | https://aistudio.google.com → Get API key | `GEMINI_API_KEY` |

```bash
cp .env.example .env     # PowerShell: Copy-Item .env.example .env
# then edit .env and paste your keys
```

**You can leave both empty.** The gateway falls back to its built-in **mock
provider** — every feature (caching, coalescing, rate limits, budgets, UI,
load tests) works identically at $0. Failover order is
`PROVIDERS_ORDER=groq,gemini,mock`; set it to just `mock` to force mock mode
even with keys present (recommended for big load tests).

### 2.2 Gateway API keys (what *clients* use)

Internal, scoped, revocable keys that applications send on every request via
the `X-API-Key` header (or `Authorization: Bearer ...`).

- A demo key **`gw_demo`** is auto-created at startup (team `demo`, unlimited
  rpm, no per-key budget) — the UI and k6 use it by default.
- Create real scoped keys via the admin API (section 5).

### 2.3 Admin key (control plane)

Header `X-Admin-Key` for `/admin/keys` management. Default: **`admin-secret`**
(change via `ADMIN_KEY` in `.env`).

---

## 3. Start the cluster

```bash
docker compose up --build -d
```

First build takes a few minutes (Maven build inside Docker). Then:

| URL | What |
|---|---|
| http://localhost:8080 | **Demo UI** + API (through the Nginx load balancer, 3 replicas behind it) |
| http://localhost:3000 | Grafana — login `admin` / `admin`, dashboard **"LLM Gateway"** is pre-provisioned |
| http://localhost:9090 | Prometheus |
| http://localhost:6333/dashboard | Qdrant UI (inspect cached vectors) |

Useful commands:

```bash
docker compose ps                        # service status
docker compose logs -f gateway1          # follow one replica's logs
docker compose up --build -d gateway1 gateway2 gateway3   # rebuild after code changes
docker compose down                      # stop everything
docker compose down -v                   # stop + wipe Redis/Qdrant state
```

### Run a single instance without Docker (dev mode)

Needs local Redis (`docker run -p 6379:6379 redis:7-alpine`) and Qdrant
(`docker run -p 6333:6333 qdrant/qdrant`):

```bash
cd backend
./mvnw spring-boot:run        # Windows: .\mvnw.cmd spring-boot:run
```

---

## 4. Demo walkthrough (UI)

Open http://localhost:8080:

1. Leave the key as `gw_demo`, hit **Send** → badges show `cache: miss`, the
   provider, latency, cost, and **which instance** (gw1/gw2/gw3) served it.
2. Hit **Send** again unchanged → `cache: exact`, ~5–10 ms, `saved $...`,
   usually a *different* instance — that's the shared Redis cache.
3. Reword the prompt (e.g. "What is a vector database?" → "Tell me about
   vector databases") → `cache: semantic`.
4. The right panel shows live cluster-wide stats (requests, hit rate, spend,
   savings, coalesced calls, rejections) shared via Redis.

## 5. Using the API

### Chat (OpenAI-compatible shape)

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" -H "X-API-Key: gw_demo" \
  -d '{"messages":[{"role":"user","content":"What is a distributed cache?"}],"temperature":0}'
```

PowerShell:

```powershell
Invoke-RestMethod http://localhost:8080/v1/chat/completions -Method Post `
  -Headers @{ "X-API-Key" = "gw_demo" } -ContentType "application/json" `
  -Body '{"messages":[{"role":"user","content":"What is a distributed cache?"}],"temperature":0}'
```

The response is OpenAI-style plus a `gateway` block:

```json
"gateway": {
  "cache": "exact | semantic | miss",
  "provider": "groq | gemini | mock",
  "instance": "gw1",
  "latency_ms": 7,
  "cost_usd": 0.0,
  "saved_usd": 0.000004,
  "compressed": false,
  "tokens_saved_by_compression": 0,
  "coalesced": false
}
```

Optional per-request controls (add to the JSON body):

| Field | Effect |
|---|---|
| `"cache": false` | bypass both caches for this request |
| `"compress": false` | skip prompt compression |
| `"stream": true` | SSE streaming (OpenAI chunk format, ends with `data: [DONE]`) |
| `"temperature": 0.9` | anything above `SEMANTIC_MAX_TEMPERATURE` (0.7) is treated as creative → never cached |
| `"max_tokens": 200` | passed through to the provider |

Note: **all JSON fields are snake_case** (`max_tokens`, `monthly_budget_usd`, ...).

### Streaming example

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" -H "X-API-Key: gw_demo" \
  -d '{"messages":[{"role":"user","content":"Hello"}],"stream":true}'
```

### Admin: keys, budgets, stats

```bash
# Create a scoped key: 60 requests/min, $5/month budget, attributed to team "payments"
curl -X POST http://localhost:8080/admin/keys \
  -H "X-Admin-Key: admin-secret" -H "Content-Type: application/json" \
  -d '{"name":"checkout-service","team":"payments","rpm":60,"monthly_budget_usd":5}'
# → returns {"key":"gw_ab12..."} — give that to the app

curl http://localhost:8080/admin/keys -H "X-Admin-Key: admin-secret"       # list keys
curl -X DELETE http://localhost:8080/admin/keys/gw_ab12... -H "X-Admin-Key: admin-secret"  # revoke

curl http://localhost:8080/admin/stats            # live cluster stats (open, powers the UI)
curl "http://localhost:8080/admin/similarity?a=hello%20world&b=hi%20world"  # embedding debug
```

`rpm: 0` = unlimited; `monthly_budget_usd: 0` = use the default (`KEY_BUDGET_USD`, $10).

### What rejections look like

| HTTP | `error.type` | Cause |
|---|---|---|
| 401 | `unauthorized` | missing/unknown/revoked API key |
| 429 | `rate_limited` | key over its per-minute limit (aggregate across all instances) |
| 402 | `budget_exceeded` | key or org monthly spend cap reached |
| 503 | `overloaded` | backpressure — instance at `MAX_CONCURRENT` in-flight requests |
| 502 | `all_providers_failed` | every provider failed / circuit-open (can't happen with `mock` in the order) |

---

## 6. Verify the distributed behavior

With the cluster up:

```bash
python scripts/distributed_check.py
```

Expected output (all three must PASS):

```
[PASS] shared cache: ... 6/6 exact hits served by instances ['gw1','gw2','gw3']
[PASS] single-flight: 20 concurrent identical requests -> 1 provider call(s), 19 coalesced ...
[PASS] rate limit: key limited to 10 rpm -> 10 accepted, 20 rejected (429) ...
```

---

## 7. Load testing (k6)

**Use mock mode for load tests** so 100K+ requests cost $0 and don't burn free
tiers: either leave `.env` keys empty or set `PROVIDERS_ORDER=mock`.

### With k6 installed locally

```bash
k6 run k6/load_test.js                                # ~5 min @ 100 VUs
k6 run -e VUS=150 -e DURATION=15m k6/load_test.js     # long run → 100K+ requests
```

### Without k6 (Docker image, runs on the compose network)

```powershell
docker run --rm --network llmopsgateway_default -v "${PWD}\k6:/scripts" `
  -e BASE=http://nginx:80 -e VUS=100 -e DURATION=10m grafana/k6 run /scripts/load_test.js
```

(Linux/macOS: `-v "$PWD/k6:/scripts"`.)

Traffic mix (realistic, mostly unique): 20% repeated (exact hits), 20%
reworded (semantic hits), 60% genuinely unique (misses), plus 30-VU bursts of
identical fresh prompts (coalescing).

### Reading the results

The k6 summary gives you most résumé numbers directly:

- `http_reqs` → total requests + **throughput (req/s)**
- `http_req_duration` p(90)/p(95) → **latency percentiles** (p50/p99 per-panel in Grafana)
- `gateway_cache_exact` / `gateway_cache_semantic` / `gateway_cache_miss` → **hit rates**
- `gateway_cached_latency` → **warm cached-response latency**
- `gateway_coalesced` → **provider calls saved by single-flight**

Cross-check cluster-wide totals in `curl http://localhost:8080/admin/stats`
and watch it live in Grafana (http://localhost:3000 → "LLM Gateway").

Reference numbers from measured runs are in [RESULTS.md](RESULTS.md):
~47% hit rate / ~43% spend avoided on the realistic mix; ~1,500 req/s on the
cache-saturation stress mix.

### Measuring token-cost reduction (on vs off)

```bash
# Run 1 — everything on (defaults). Note cost_usd + saved_usd from /admin/stats.
docker compose down -v && docker compose up -d
k6 run -e DURATION=5m k6/load_test.js
curl http://localhost:8080/admin/stats

# Run 2 — optimizations off. Compare cost_usd between runs.
docker compose down -v
EXACT_CACHE_ENABLED=false SEMANTIC_CACHE_ENABLED=false COMPRESSION_ENABLED=false docker compose up -d
k6 run -e DURATION=5m k6/load_test.js
curl http://localhost:8080/admin/stats
```

(On Windows set those three variables in `.env` instead of inline.)
`docker compose down -v` between runs wipes Redis/Qdrant so runs are comparable.
Reduction % = `1 − (cost_run1 / cost_run2)`.

---

## 8. Tuning the semantic threshold

```bash
python scripts/threshold_eval.py
```

Sweeps thresholds 0.60–0.98 over the labeled pairs in `scripts/pairs.json`
(edit that file to add your own domain's paraphrases) and prints hit-rate vs
false-hit-rate per threshold plus a suggestion.

- Current default **0.66** — data-justified for the local hash embedder
  (62% hit / 0% false-hit).
- Set via `SEMANTIC_THRESHOLD` in `.env`, then restart the gateways.
- Switching to real neural embeddings? Set `EMBEDDING_PROVIDER=gemini`,
  `EMBEDDING_DIMENSION=768` (needs `GEMINI_API_KEY`), wipe Qdrant
  (`docker compose down -v` — the collection dimension changes), re-run the
  eval, and expect the sweet spot around 0.85+.

---

## 9. Configuration reference (`.env` / env vars)

| Variable | Default | Meaning |
|---|---|---|
| `GROQ_API_KEY` / `GEMINI_API_KEY` | *(empty)* | provider keys; empty → mock fallback |
| `PROVIDERS_ORDER` | `groq,gemini,mock` | failover order; `mock` for zero-cost testing |
| `EMBEDDING_PROVIDER` | `hash` | `hash` (local) or `gemini` (hosted, semantic) |
| `EMBEDDING_DIMENSION` | `384` | 384 for hash, 768 for gemini |
| `SEMANTIC_THRESHOLD` | `0.66` | min cosine similarity for a semantic hit |
| `SEMANTIC_CACHE_TTL` / `EXACT_CACHE_TTL` | `3600` | cache entry expiry (seconds) |
| `SEMANTIC_MAX_TEMPERATURE` | `0.7` | requests hotter than this are never cached |
| `EXACT_CACHE_ENABLED` / `SEMANTIC_CACHE_ENABLED` / `COMPRESSION_ENABLED` | `true` | feature toggles |
| `COMPRESSION_MIN_WORDS` | `40` | prompts shorter than this are never compressed |
| `DEFAULT_RPM` | `120` | rate limit for keys created with `rpm: 0`* |
| `ORG_BUDGET_USD` | `25` | org-wide monthly spend cap |
| `KEY_BUDGET_USD` | `10` | default per-key monthly cap |
| `BUDGET_ENFORCE` | `true` | `false` = track spend but never reject |
| `MAX_CONCURRENT` | `64` | backpressure: max in-flight requests per instance |
| `CB_FAILURE_THRESHOLD` / `CB_COOLDOWN_SECONDS` | `3` / `30` | circuit breaker tuning |
| `MOCK_LATENCY_MS` | `200` | simulated provider latency for the mock |
| `ADMIN_KEY` | `admin-secret` | control-plane header value |
| `DEMO_KEY` | `gw_demo` | auto-created demo key name |

\* the `gw_demo` key is special-cased to unlimited so load tests aren't throttled.

Per-model pricing (USD per 1M input/output tokens) lives in
`backend/src/main/resources/application.yml` under `gateway.pricing`.

---

## 10. Build & test the backend directly

```bash
cd backend
./mvnw package          # compiles + runs the unit tests (Windows: .\mvnw.cmd)
```


---

## 11. Troubleshooting

| Symptom | Fix |
|---|---|
| `502 Bad Gateway` from Nginx right after startup | gateways still booting (~15 s); check `docker compose logs gateway1` |
| `docker compose` can't reach the daemon | start Docker Desktop first |
| Every request is `cache: miss` on reworded prompts | similarity below threshold — run `scripts/threshold_eval.py`, lower `SEMANTIC_THRESHOLD` |
| Semantic cache silently inactive | Qdrant down or dimension mismatch after switching embedders → `docker compose down -v && docker compose up -d` |
| `402 budget_exceeded` during long tests | raise `ORG_BUDGET_USD` in `.env` or set `BUDGET_ENFORCE=false`, restart gateways |
| `429` during load test with a custom key | create the key with `"rpm": 0` (unlimited) or use `gw_demo` |
| Provider errors with real keys | check the key, then note the gateway auto-fails-over (watch `gateway.provider.calls` in Grafana) |
| Stale responses while developing | caches persist in Redis — `docker compose down -v` wipes state |
