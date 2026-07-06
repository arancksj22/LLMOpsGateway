# Distributed LLM Gateway

A self-hostable **Spring Boot** gateway that applications route their LLM calls
through instead of calling providers (Groq / Gemini / OpenAI-compatible)
directly. One controlled path for all LLM traffic: it **avoids calls** it can
(exact + semantic caching, request coalescing), **shrinks calls** it can't
(prompt compression), and **centrally controls and observes everything**
(scoped keys, budgets, rate limits, Prometheus/Grafana).

Demo UI included — send a prompt, watch it come back as an exact/semantic
cache hit with latency, tokens, cost and the serving instance.

> **Full operating manual** — every command, key, endpoint, load-test and
> tuning workflow — is in **[USAGE.md](USAGE.md)**.

## Why centralized?

Per-app libraries can't do the things that require seeing **all** traffic at once:

- **Org-wide spend caps & cost attribution** — "the company spends at most $X/month",
  attributed per key/team, enforced with atomic Redis counters. No single app can do this.
- **Centralized API-key management** — real provider keys live server-side only;
  apps get scoped, revocable internal keys (`POST /admin/keys`).
- **Global rate limiting** — providers rate-limit your whole org; only a central
  point sees total traffic and can throttle under that ceiling.
- **Single point of policy, provider switching & observability** — every call's
  cost/latency/tokens in one Grafana dashboard.

Caching alone doesn't justify a gateway (it works per-app); centralization does.
The efficiency features ride on top of it.

## Why distributed?

The gateway is shared critical infrastructure on the request path, so it runs
as **multiple stateless replicas behind Nginx**: throughput beyond one
instance, no single point of failure, rolling deploys. All shared state lives
in central stores every instance reaches:

- **Shared response cache** — Redis (exact) + Qdrant (semantic vectors): a
  response cached by one instance is served by any other.
- **Single-flight coalescing** — concurrent identical cache misses across
  *all* instances trigger one provider call (Redis lock + shared result).
- **Distributed rate limiting & budgets** — atomic Redis counters hold in aggregate.

> **Honest scope note:** at small scale a single instance suffices. Distribution
> here demonstrates the *capability* — validated with a local docker-compose
> cluster and k6 load testing — not a claim of production traffic or a scaled
> cloud deployment. The public demo is a single free-tier instance.

## Architecture

```
clients ──> Nginx LB ──> gateway1 / gateway2 / gateway3   (stateless Spring Boot)
                            │            │
                            │            ├── Redis    — exact cache, rate limits, budgets,
                            │            │              single-flight locks, keys, live stats
                            │            └── Qdrant   — semantic cache vectors
                            │
                            └── providers: Groq -> Gemini -> mock (ordered failover,
                                           per-provider circuit breakers)

Prometheus scrapes /actuator/prometheus on every replica -> Grafana dashboard
```

**Request pipeline:** auth → rate limit → backpressure (bounded in-flight, clean
503s) → prompt compression → exact cache → semantic cache (embedding + vector
search, tunable threshold, TTL, skip-if-high-temperature policy) → budget check
→ single-flight → provider failover → cache store + cost accounting + metrics.

**Embeddings** are behind an interface: `hash` (local, zero-dependency,
offline-friendly) or `gemini` (free hosted `text-embedding-004`, real semantic
similarity). A **mock provider** is the final failover so the whole cluster and
100K-request load tests run with **no API keys at $0**.

## Run the local cluster

Prereqs: Docker. Optionally free API keys from [Groq](https://console.groq.com)
and [Google AI Studio](https://aistudio.google.com).

```bash
cp .env.example .env        # optionally add GROQ_API_KEY / GEMINI_API_KEY
docker compose up --build
```

| URL | What |
|---|---|
| http://localhost:8080 | Demo UI + API via the load balancer |
| http://localhost:3000 | Grafana (admin/admin), "LLM Gateway" dashboard provisioned |
| http://localhost:9090 | Prometheus |

No keys? Everything still works — requests fall through to the built-in mock provider.

### Use the API

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" -H "X-API-Key: gw_demo" \
  -d '{"messages":[{"role":"user","content":"What is a distributed cache?"}],"temperature":0}'
```

OpenAI-style shape; the response carries a `gateway` block (`cache`:
exact/semantic/miss, `latency_ms`, `cost_usd`, `saved_usd`, `provider`,
`instance`, `coalesced`, compression info). Optional per-request controls:
`"cache": false`, `"compress": false`, `"stream": true` (SSE passthrough).

### Manage keys, budgets, stats

```bash
# create a scoped key: 60 req/min, $5/month
curl -X POST http://localhost:8080/admin/keys -H "X-Admin-Key: admin-secret" \
  -H "Content-Type: application/json" \
  -d '{"name":"checkout-service","team":"payments","rpm":60,"monthly_budget_usd":5}'

curl http://localhost:8080/admin/keys -H "X-Admin-Key: admin-secret"   # inspect
curl -X DELETE http://localhost:8080/admin/keys/gw_... -H "X-Admin-Key: admin-secret"  # revoke
curl http://localhost:8080/admin/stats                                  # live cluster stats
```

All thresholds, limits, budgets and per-model pricing are configuration
(`backend/src/main/resources/application.yml`, overridable via env / `.env`).

## Validate distributed behavior

With the cluster up (Python 3, stdlib only):

```bash
python scripts/distributed_check.py
```

Checks shared cache across instances, single-flight coalescing (~20 concurrent
identical requests → 1 provider call), and a 10-rpm key holding in aggregate
across all replicas.

## Load test (k6)

```bash
k6 run k6/load_test.js                          # ~5 min @ 100 VUs
k6 run -e VUS=150 -e DURATION=15m k6/load_test.js   # longer run for 100K+ requests
```

Traffic mix: 40% repeated (exact hits), 30% reworded (semantic hits), 30%
unique (misses), plus bursts of concurrent identical prompts (coalescing).
Custom metrics in the k6 summary: `gateway_cache_exact/semantic/miss`,
`gateway_coalesced`, `gateway_cached_latency`. Run against the mock provider
(default with no keys) so it costs $0.

To measure token-cost reduction, run once with caching/compression on, once
with `EXACT_CACHE_ENABLED=false SEMANTIC_CACHE_ENABLED=false
COMPRESSION_ENABLED=false`, and compare `cost_usd` in `/admin/stats`.

## Tune the semantic threshold

```bash
python scripts/threshold_eval.py
```

Sweeps the similarity threshold over a labeled set of equivalent /
non-equivalent query pairs (via `/admin/similarity`, i.e. the exact embedding
the cache uses) and prints hit-rate vs false-hit-rate per threshold plus a
suggested value — so the configured `SEMANTIC_THRESHOLD` is data-justified.

## Deployment (public demo)

Target: **Render free tier**, single instance (Dockerfile in `backend/`), with
Upstash/Redis Cloud free Redis and Qdrant Cloud free tier. Notes: Render free
services cold-start after ~15 min idle; on the 512MB tier use
`EMBEDDING_PROVIDER=gemini` (hosted embeddings) to keep memory low. The
multi-instance behavior is demonstrated locally, not on Render.

## Results (measured)

> Full methodology, raw k6 summaries and reproduction commands: **[RESULTS.md](RESULTS.md)**.
> Measured on the local 3-replica cluster with the mock provider (real pricing applied).

| Metric | Value |
|---|---|
| Requests benchmarked | 1,339,867 in 15 min (k6, local 3-replica cluster) |
| Token-cost reduction (caching + compression on vs off) | ~98% per request |
| Cache hit rate (exact / semantic) | 68.6% / 30.7% (99.2% combined) |
| Warm cached-response latency | ~40 ms median (p99 455 ms) |
| Throughput | ~1,500 req/s sustained |
| Latency p50 / p95 / p99 | 41 / 342 / 513 ms |
| Coalescing (20-way concurrent-identical burst) | 1–2 provider calls, rest coalesced |
| Distributed rate limit (10-rpm key, 30-req burst) | exactly 10 accepted across 3 instances |
| Prompt compression (verbose redundant prompt) | 66% prompt-token reduction |
| Chosen semantic threshold (hit vs false-hit) | 0.66 (62% / 0%) |

### Impact summary

- **Efficiency:** cut token costs ~98% and served warm cached responses in
  ~40 ms (median) by layering exact-match deduplication, semantic caching
  (embeddings + vector DB) and token-importance prompt compression, measured
  across 1.3M+ benchmarked requests.
- **Distributed systems:** scaled horizontally behind a load balancer with
  shared cache/vector state across stateless instances, single-flight request
  coalescing collapsing 20 concurrent duplicate cache misses into 1–2 provider
  calls, and atomic Redis-backed rate limiting enforcing per-key quotas
  exactly across all nodes.
- **Centralized control:** server-side provider-key storage with scoped
  revocable per-app keys, org-wide spend-cap enforcement with per-team cost
  attribution, and full usage/cost/latency observability via Prometheus + Grafana.
- **Reliability:** multi-provider fallback with circuit breaking, SSE streaming
  passthrough, and backpressure via bounded in-flight work, validated under k6
  load at ~1,500 req/s and 513 ms p99 with zero unexpected failures.

## Repo layout

```
backend/    Spring Boot gateway (+ demo UI in src/main/resources/static)
nginx/      load balancer config
prometheus/ scrape config          grafana/  provisioned dashboard
k6/         load test              scripts/  threshold eval + distributed checks
```
