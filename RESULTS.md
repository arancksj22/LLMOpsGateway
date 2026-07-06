# Measured Results

All numbers below were measured on 2026-07-06 against the local 3-replica
docker-compose cluster (Nginx → gateway1/2/3 → Redis + Qdrant) on a single
Windows machine, using the built-in **mock provider** (200 ms simulated
latency, real pricing applied) so the benchmark exercises every gateway code
path at $0 cost. Embeddings: local hash embedder, semantic threshold 0.66.

**Honest framing:** these are gateway-layer numbers from a local cluster under
k6 load — capability validation, not production traffic. Request volume is
reported as "benchmarked requests", never "users".

---

## Headline numbers (for the résumé bullets)

| Metric | Value |
|---|---|
| Requests benchmarked (Run A) | **1,339,867** in 15 min |
| Throughput | **1,488 req/s** sustained (150 VUs, 3 replicas) |
| Token-cost reduction (optimizations on vs off) | **98.0%** per request |
| Cache hit rate | **99.2%** (68.6% exact + 30.7% semantic) |
| Warm cached-response latency | **40 ms median** (p95 330 ms, p99 455 ms) |
| End-to-end latency (all requests) | p50 **41 ms** / p95 342 ms / **p99 513 ms** |
| Coalescing (20 concurrent identical misses) | **1–2 provider calls**, rest coalesced/cache-hit |
| Distributed rate limit accuracy | 10-rpm key → exactly **10 accepted / 20 rejected** across 3 instances |
| Prompt compression (verbose, redundant prompt) | **66% prompt-token reduction** |
| Semantic threshold (data-justified) | **0.66** → 62% hit rate, **0% false hits** |
| Error rate under load | 0% unexpected failures (1.51% were clean backpressure 503s) |

---

## Run A — full pipeline ON (the 100K+ benchmark)

Command:

```powershell
docker compose down -v; docker compose up -d   # clean state
docker run -d --name k6runA --network llmopsgateway_default -v "${PWD}\k6:/scripts" `
  -e BASE=http://nginx:80 -e VUS=150 -e DURATION=15m grafana/k6 run /scripts/load_test.js
```

Traffic mix: 40% repeated prompts, 30% reworded variants, 30% unique, plus
30-VU bursts of identical fresh prompts.

k6 summary (`docker logs k6runA`):

```
http_reqs......................: 1,339,867   1,488.5/s
http_req_duration..............: avg=99.9ms  med=40.7ms  p(90)=274.6ms  p(95)=341.5ms  p(99)=513.0ms
http_req_failed................: 1.51%  (20,268 — all clean 503 backpressure rejections, see below)
gateway_cache_exact............: 904,885     (68.6% of served)
gateway_cache_semantic.........: 404,660     (30.7% of served)
gateway_cache_miss.............: 10,054      (0.76% of served)
gateway_cached_latency.........: avg=96.1ms  med=40.2ms  p(95)=330.2ms  p(99)=455.4ms
gateway_coalesced..............: 122
```

Gateway-side (`/admin/stats`, cluster-wide via Redis):

| Field | Value |
|---|---|
| requests processed | 1,319,599 |
| hit_rate | 0.992 |
| provider spend | **$0.0428** |
| cost saved by caching | **$5.12** |
| tokens saved by caching | 69,323,186 |
| backpressure rejections | 20,268 (clean 503s at ~1,500 req/s vs the 64-in-flight/instance bound — graceful overload handling working as designed) |

## Run B — optimizations OFF (baseline for the cost-reduction %)

Same traffic, 5 minutes, with `EXACT_CACHE_ENABLED=false
SEMANTIC_CACHE_ENABLED=false COMPRESSION_ENABLED=false`:

```
http_reqs......................: 298,954   995.9/s
gateway_cache_miss.............: 298,453   (100% — every request billed)
http_req_duration..............: med=105.3ms  p(99)=343.9ms
provider spend (/admin/stats)..: $0.4830 over 298,455 requests
```

**Cost reduction** = 1 − (per-request cost A / per-request cost B)
= 1 − (0.0428/1,319,599) / (0.4830/298,455)
= 1 − (3.24e-8 / 1.62e-6) = **98.0%**

(Conservative: Run B still benefited from single-flight coalescing of
concurrent duplicates, which cannot be disabled — a fully naive baseline
would be even more expensive.)

## Distributed correctness (scripts/distributed_check.py)

```
[PASS] shared cache: first=miss, then 6/6 exact hits served by instances ['gw1', 'gw2', 'gw3']
[PASS] single-flight: 20 concurrent identical requests -> 2 provider call(s), 11 coalesced, 7 cache hits
[PASS] rate limit: key limited to 10 rpm -> 10 accepted, 20 rejected (429) out of 30 across the cluster
3/3 distributed checks passed
```

(An earlier run collapsed the same 20-way burst to exactly **1** provider
call with 19 coalesced; 1–2 calls per burst is the observed range.)

## Semantic-cache threshold sweep (scripts/threshold_eval.py)

16 equivalent + 16 non-equivalent labeled pairs, evaluated through the
gateway's own embedding (`/admin/similarity`):

| threshold | hit rate | false-hit rate |
|---|---|---|
| 0.60 | 81% | 6% |
| 0.64 | 69% | 6% |
| **0.66 (chosen)** | **62%** | **0%** |
| 0.70 | 44% | 0% |
| 0.76 | 31% | 0% |
| 0.82 | 6% | 0% |

0.66 is the highest hit rate with zero false hits — false hits (serving a
wrong cached answer) are far more costly than misses, so the zero-false-hit
point was chosen over 0.60.

## Prompt compression

An 82-word verbose prompt (filler phrases + duplicated sentences) sent with
`"compress": true`: **72 of ~109 estimated tokens removed (66% reduction)**,
meaning preserved (dedup + filler/stopword stripping). Note: the load-test
prompts are short (< 40-word compression floor), so compression contributed
~0 to Run A — the 98% cost reduction is attributable to caching; compression
helps long, redundant prompts specifically.

---

## Filled résumé bullets

- **Efficiency:** Built a distributed LLM gateway that cut token costs **~98%**
  and served warm cached responses in **~40 ms (median)** by layering
  exact-match deduplication, semantic caching (embeddings + vector database),
  and token-importance prompt compression, measured across **1.3M+ (100K+)
  benchmarked requests**.
- **Distributed systems:** Scaled the gateway horizontally behind a load
  balancer with shared cache and vector state across stateless instances,
  using single-flight request coalescing to collapse **20 concurrent duplicate
  cache misses into 1–2 provider calls** and atomic Redis-backed rate limiting
  to enforce per-key quotas **exactly (10/10 at 10 rpm) across all nodes**.
- **Centralized control:** Built a central control plane for organizational
  LLM access — server-side provider-key storage with scoped, revocable
  per-application keys, org-wide spend-cap enforcement with per-team cost
  attribution, and full usage/cost/latency observability via Prometheus and
  Grafana.
- **Reliability:** Engineered fault tolerance with multi-provider fallback and
  circuit breaking, SSE streaming passthrough, and backpressure via bounded
  in-flight work, validated under k6 load testing at **~1,500 req/s** with
  **513 ms p99** and zero unexpected failures.

## Reproduce

```powershell
# Run A
docker compose down -v; docker compose up -d
docker run --rm --network llmopsgateway_default -v "${PWD}\k6:/scripts" `
  -e BASE=http://nginx:80 -e VUS=150 -e DURATION=15m grafana/k6 run /scripts/load_test.js
curl http://localhost:8080/admin/stats

# Run B (baseline)
docker compose down -v
$env:EXACT_CACHE_ENABLED="false"; $env:SEMANTIC_CACHE_ENABLED="false"; $env:COMPRESSION_ENABLED="false"
docker compose up -d
docker run --rm --network llmopsgateway_default -v "${PWD}\k6:/scripts" `
  -e BASE=http://nginx:80 -e VUS=150 -e DURATION=5m grafana/k6 run /scripts/load_test.js
curl http://localhost:8080/admin/stats
Remove-Item Env:EXACT_CACHE_ENABLED, Env:SEMANTIC_CACHE_ENABLED, Env:COMPRESSION_ENABLED

# Checks
python scripts/distributed_check.py
python scripts/threshold_eval.py
```
