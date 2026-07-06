<div align="center">

# Distributed LLMOps Gateway

**One controlled path for all of an organization's LLM traffic: cache it, coalesce it, budget it, observe it.**

*A horizontally scaled Spring Boot proxy that avoided **43% of LLM provider spend** across **140K+ benchmarked requests**, serving cached answers in **16 ms** instead of 200 ms+ provider round-trips.*

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-coordination-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![Qdrant](https://img.shields.io/badge/Qdrant-vector%20search-BC1439)](https://qdrant.tech/)
[![Docker Compose](https://img.shields.io/badge/Docker-3--replica%20cluster-2496ED?logo=docker&logoColor=white)](https://docs.docker.com/compose/)
[![k6](https://img.shields.io/badge/k6-1.3M%2B%20requests-7D64FF?logo=k6&logoColor=white)](https://k6.io/)
[![CI](https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?logo=githubactions&logoColor=white)](.github/workflows/ci.yml)

**[Quickstart](#quickstart-zero-keys-zero-config)** |
**[Measured Results](#measured-results)** |
**[How It Works](#how-a-request-flows)** |
**[Architecture Deep-Dive](ARCHITECTURE.md)** |
**[Operating Manual](USAGE.md)** |
**[Benchmarks](RESULTS.md)**

</div>

---

## The problem

Every team in a company is wiring LLM calls into their apps, and every app calls the provider directly. The result:

- **Duplicated spend.** Ten apps pay ten times for the same (or same-*meaning*) question.
- **Scattered secrets.** Provider API keys copy-pasted into every codebase.
- **No control.** Nobody can enforce *"we spend at most $X per month"* or see who spent what, because **no single app sees the total**.
- **Shared limits, unshared awareness.** Providers rate-limit the whole org; apps trip over each other blindly.

A per-app caching library cannot fix this. These problems require seeing all traffic at once, and that is exactly what this gateway is: the single, horizontally scaled path every LLM call flows through.

> *For every request: first try to **skip** the call; if we must call, make it **cheap**; and no matter what, **control and observe** it.*

## Architecture

<!-- =====================================================================
     ARCHITECTURE DIAGRAM: drop docs/architecture.png here:
     ![Architecture diagram](docs/architecture.png)
     ===================================================================== -->

```
  clients --> Nginx (round-robin LB)
                |--> gateway1 --+          3 stateless Spring Boot replicas
                |--> gateway2 --+--> Redis   . exact cache  . rate-limit counters
                |--> gateway3 --+            . spend counters . coalescing locks
                                |            . API keys . live cluster stats
                                |--> Qdrant  . semantic-cache vectors (cosine ANN)
                                |
                                +--> Providers: Groq -> Gemini -> mock
                                     (ordered failover + circuit breakers)

  Prometheus scrapes /actuator/prometheus on every replica --> Grafana dashboard
```

Instances hold **zero state**: every replica is identical and disposable. All shared state (cache, locks, counters, keys) lives in Redis and Qdrant, which is precisely what makes horizontal scaling a `docker compose` one-liner instead of a rewrite.

## Measured results

The primary benchmark uses a **realistic mostly-unique traffic mix** (60% unique / 20% repeated / 20% reworded), because cache numbers mean nothing without stating the workload. Full methodology, raw k6 output, and reproduction commands: **[RESULTS.md](RESULTS.md)**.

| Metric | Measured |
|---|---|
| Provider spend avoided | **42.6%** (tokens avoided: 40.8%) |
| Cache hit rate | **47.1%** (39.6% exact + 7.6% semantic) |
| Warm cached response | **16 ms median** (vs 200 ms+ provider baseline) |
| Requests benchmarked | **141,885** in 7 min / 150 VUs / 3 replicas |
| Stress ceiling *(repetitive traffic)* | **1,500 req/s** / p99 513 ms / 1.34M requests |
| Thundering herd | 20 concurrent identical requests to **1 provider call** |
| Distributed rate limit | 10-rpm key: **exactly 10 accepted** across 3 nodes |
| Prompt compression | **66%** token cut on verbose, redundant prompts |
| Semantic threshold | 0.66, tuned on labeled data: 62% recall, **0% false hits** |
| Failures under overload | **0% unexpected**: 100% of excess load rejected as clean 503s |

## What's inside

| Feature | The interesting bit |
|---|---|
| **Exact-match cache** | SHA-256 of the canonical request in Redis; a hit on any instance serves all instances |
| **Semantic cache** | *"Explain what X is"* hits an answer cached for *"What is X?"*: embeddings + Qdrant cosine search, tunable threshold, TTL, skip-when-creative policy |
| **Single-flight coalescing** | Concurrent identical misses **across instances** collapse into one provider call via Redis `SET NX` leader election, with crash takeover |
| **Distributed rate limiting** | Per-key quotas that hold *in aggregate* cluster-wide, one atomic Lua script per request |
| **Spend caps & attribution** | Token-priced cost per call, atomic org/team/key monthly counters, HTTP 402 when the budget is gone |
| **Key management** | Provider keys never leave the server; apps get scoped, revocable gateway keys |
| **Provider failover** | Groq to Gemini to mock, with per-provider circuit breakers (open after 3 failures, half-open probe after 30 s) |
| **SSE streaming** | Real token-by-token passthrough that still captures the full response for caching and cost accounting |
| **Backpressure** | Bounded in-flight work; overload gets an instant clean 503, never a collapse |
| **Prompt compression** | Heuristic trimmer for long, redundant prompts: measurably fewer tokens, roughly same meaning |
| **Observability** | Micrometer to Prometheus to a provisioned Grafana dashboard (p50/p95/p99, hit rates, cost per team, rejections) |
| **Demo UI** | Single-page dashboard: send a prompt, watch cache status / latency / cost / serving instance, live cluster stats |

The whole thing is **~29 classes, ~1,750 lines of Java**, deliberately small enough to read in an evening. Every mechanism above has a plain-English comment at its source.

## How a request flows

```
 auth -> rate limit -> backpressure -> compress -> exact cache -> semantic cache -> budget -> single-flight -> provider failover
         (Redis)       (semaphore)                 ~0.5 ms hit    ~5 ms hit         (402?)    (Redis lock)     (circuit breakers)
```

The ordering is itself a design decision: **cheapest checks first**. A rate-limit check costs one Redis op; an embedding plus vector search costs milliseconds; a provider call costs hundreds of milliseconds *and money*. Budgets are only checked when we are about to spend, since cache hits are free and should not be blocked by an exhausted budget. Full rationale for every decision (and its alternatives): **[ARCHITECTURE.md](ARCHITECTURE.md)**.

## Quickstart (zero keys, zero config)

The only prerequisite is Docker. A built-in mock provider means **no API keys, no signups**: the full cluster works out of the box.

```bash
git clone https://github.com/arancksj22/LLMOpsGateway.git && cd LLMOpsGateway
docker compose up --build        # ~2 min first build
```

| Service | URL |
|---|---|
| **Demo UI** | http://localhost:8080 |
| **Grafana** | http://localhost:3000 (admin/admin, dashboard pre-provisioned) |
| **Prometheus** | http://localhost:9090 |

**The 60-second demo:** open the UI, send a prompt (`miss`, ~200 ms), send it again (`exact` hit, ~10 ms, served by a *different instance*, `saved $`), reword it (`semantic` hit), and watch the live cluster stats climb.

Real providers are two free keys away ([Groq](https://console.groq.com), [Google AI Studio](https://aistudio.google.com)):

```bash
cp .env.example .env    # paste GROQ_API_KEY / GEMINI_API_KEY
docker compose up -d
```

### Use it as an API (OpenAI-compatible)

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" -H "X-API-Key: gw_demo" \
  -d '{"messages":[{"role":"user","content":"What is a distributed cache?"}],"temperature":0}'
```

Responses carry a `gateway` block (cache status, latency, cost, savings, serving instance, coalescing) so the value is visible on every call. Per-request controls: `"cache": false`, `"compress": false`, `"stream": true`. Key management, budgets, and every endpoint: **[USAGE.md](USAGE.md)**.

## Prove the distributed claims yourself

Distributed behavior is **verified, not asserted**: one script, three properties, against the live cluster.

```bash
python scripts/distributed_check.py
```

```
[PASS] shared cache:   miss once, then 6/6 exact hits served by gw1, gw2 AND gw3
[PASS] single-flight:  20 concurrent identical requests -> 1 provider call
[PASS] rate limit:     10-rpm key -> exactly 10 accepted, 20x HTTP 429, across all nodes
```

Load test (mock provider means $0 at any volume) and threshold tuning:

```bash
k6 run k6/load_test.js               # realistic traffic mix, custom cache metrics
python scripts/threshold_eval.py     # sweep semantic threshold on labeled pairs
```

## Design highlights

- **State placement is the whole game.** Rate limits, budgets, and locks live in Redis (they must be consistent). Circuit breakers stay in-memory (provider health is an *observation*, not shared state: worst case is 9 extra failures cluster-wide, versus a Redis round-trip on every request). Knowing which is which is the design.
- **Precision over recall in the semantic cache.** The threshold (0.66) was chosen by sweeping labeled paraphrase pairs: the highest hit rate at **zero false hits**, because confidently serving a *wrong* cached answer is the one unforgivable failure mode.
- **Everything is config, nothing is hardcoded.** Thresholds, TTLs, budgets, per-model pricing, provider order: one typed record tree, all env-overridable.
- **Deliberately boring where boring wins.** Nginx over a service mesh, compose over K8s, polling over pub/sub, ~60-line hand-rolled provider adapters over an LLM framework. Every "no" is defended in [ARCHITECTURE.md](ARCHITECTURE.md).

**Honest scope:** this demonstrates distributed *capability* on a local cluster with free-tier/mock providers, validated by load tests rather than production traffic. Known limitations (fixed-window burst edges, TTL-only invalidation, single-node Redis) are documented with their upgrade paths.

## Documentation

| Doc | What's in it |
|---|---|
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | Every design decision with alternatives and trade-offs, a distributed-correctness cheat sheet, 27 interview-style Q&As |
| **[USAGE.md](USAGE.md)** | Complete operating manual: keys, endpoints, config reference, load testing, troubleshooting |
| **[RESULTS.md](RESULTS.md)** | Benchmark methodology, raw k6 summaries, cost math, reproduction commands |

## Repo layout

```
backend/     Spring Boot gateway (~29 classes) + demo UI (single static page)
nginx/       load balancer          prometheus/  scrape config
grafana/     provisioned dashboard  k6/          load-test script (traffic-mix driven)
scripts/     distributed checks . semantic-threshold evaluation . labeled dataset
```

---

<div align="center">

**Stack:** Java 21 / Spring Boot 4 (MVC + virtual threads) / Redis / Qdrant / Nginx / Docker Compose / Prometheus + Grafana / k6 / GitHub Actions

*Built as a deep-dive into distributed systems and LLM cost engineering. Every number in this README is measured, reproducible, and honestly framed.*

</div>
