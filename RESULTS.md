# Measured Results

Measured 2026-07-07 on the local 3-replica docker-compose cluster (Nginx →
gateway1/2/3 → Redis + Qdrant) on a single Windows laptop, using the built-in
**mock provider** (200 ms simulated latency, real pricing applied) so the
benchmark exercises every gateway code path at $0. Embeddings: local hash
embedder, semantic threshold 0.66.

**Honest framing:** gateway-layer numbers from a local cluster under k6 load —
capability validation, not production traffic. Hit rate and savings are
functions of the traffic mix, so the primary benchmark uses a **realistic,
mostly-unique workload** (60% unique / 20% repeated / 20% reworded); a
near-fully-repetitive stress run is reported separately as the upper bound.

---

## Headline numbers (primary benchmark — realistic workload)

| Metric | Value |
|---|---|
| Requests benchmarked | **141,885** in 7 min (150 VUs, 3 replicas) |
| Cache hit rate | **47.1%** (39.6% exact + 7.6% semantic) |
| Provider spend avoided | **42.6%** ($0.258 saved of $0.605 potential) |
| Provider tokens avoided | **40.8%** (3.48M of 8.52M) |
| Warm cached-response latency | **16 ms median** (p99 507 ms) |
| End-to-end latency | p50 468 ms / p95 1.18 s / p99 1.71 s at ~340 req/s |
| Coalescing (20 concurrent identical misses) | **1–2 provider calls**, verified separately |
| Distributed rate limit accuracy | 10-rpm key → exactly **10 accepted / 20 rejected** across 3 instances |
| Prompt compression (verbose, redundant prompt) | **66% prompt-token reduction** |
| Semantic threshold (data-justified) | **0.66** → 62% paraphrase recall, **0% false hits** |
| Failures | 0% unexpected (0.84% were clean backpressure 503s) |

## Benchmark 1 — realistic workload (primary)

Traffic mix modeled on real LLM usage, which is mostly unique queries:
**60% unique** (collision-free generated questions), **20% repeated**
(a pool of 10 fixed prompts), **20% reworded** (paraphrase variants), plus
30-VU bursts of identical fresh prompts.

```powershell
docker compose down -v; docker compose up -d
docker run --rm --network llmopsgateway_default -v "${PWD}\k6:/scripts" `
  -e BASE=http://nginx:80 -e VUS=150 -e DURATION=7m grafana/k6 run /scripts/load_test.js
```

k6 summary (optimizations ON):

```
http_reqs......................: 141,885   337.6/s
gateway_cache_exact............: 55,651    (39.6% of served)
gateway_cache_semantic.........: 10,670    (7.6% of served)
gateway_cache_miss.............: 74,367    (52.9% of served)
gateway_cached_latency.........: med=16.2ms   p(95)=219.9ms  p(99)=507.1ms
http_req_duration..............: med=467.8ms  p(95)=1.18s    p(99)=1.71s
http_req_failed................: 0.84% (1,197 — all clean 503 backpressure rejections)
```

Gateway-side (`/admin/stats`, cluster-wide):

| Field | Value |
|---|---|
| requests processed | 140,688 |
| provider spend | $0.3476 |
| spend avoided by caching | $0.2578 → **42.6% of the $0.6054 potential** |
| tokens used / avoided | 5.04M / **3.48M (40.8%)** |
| backpressure rejections | 1,195 clean 503s |

**Baseline comparison (optimizations OFF, same mix, 3 min):** 141,077
requests at 783 req/s, 100% misses, $0.4435 over 140,669 requests. Per-request
cost: $3.15e-6 (off) vs $2.47e-6 (on) → **21.6% cheaper per request**. This
A/B number is *lower* than the 42.6% spend-avoided figure because the cache
disproportionately catches the short, cheap repeated prompts while the
expensive unique prompts always miss — a real workload effect, reported
as-is. (The baseline also still benefits from single-flight coalescing, which
can't be disabled, making both numbers conservative.)

**Notable trade-off surfaced by this benchmark:** the all-miss baseline is
actually *faster* end-to-end (p50 205 ms vs 468 ms) because misses in the
cached configuration pay for embedding + vector search + cache writes on a
CPU-contended laptop. Semantic caching buys cost, not miss-latency — worth it
when hit-rate × provider-cost outweighs the miss overhead.

## Benchmark 2 — cache-saturation stress run (upper bound)

An earlier 15-minute run with **near-fully-repetitive traffic** (its "unique"
prompts were template-generated and matched each other semantically, making
the effective workload ~99% cache-friendly). Reported as the upper bound and
as the gateway's hot-path capacity measurement:

- **1,339,867 requests at 1,488 req/s** sustained, p50 41 ms / p99 513 ms
- Hit rate 99.2%, warm cache median 40 ms, $5.12 saved vs $0.043 spent (≈98%)
- 1.51% clean backpressure 503s at ~1,500 req/s, zero unexpected failures

Quote these only with the "cache-friendly / repetitive workload" qualifier.

## Distributed correctness (scripts/distributed_check.py)

```
[PASS] shared cache: first=miss, then 6/6 exact hits served by instances ['gw1', 'gw2', 'gw3']
[PASS] single-flight: 20 concurrent identical requests -> 1 provider call(s), 12 coalesced, 7 cache hits
[PASS] rate limit: key limited to 10 rpm -> 10 accepted, 20 rejected (429) out of 30 across the cluster
3/3 distributed checks passed
```

## Semantic-cache threshold sweep (scripts/threshold_eval.py)

16 equivalent + 16 non-equivalent labeled pairs through the gateway's own
embedding (`/admin/similarity`):

| threshold | hit rate | false-hit rate |
|---|---|---|
| 0.60 | 81% | 6% |
| 0.64 | 69% | 6% |
| **0.66 (chosen)** | **62%** | **0%** |
| 0.70 | 44% | 0% |
| 0.76 | 31% | 0% |
| 0.82 | 6% | 0% |

0.66 is the highest recall with zero false hits — serving a wrong cached
answer is far worse than a miss.

## Prompt compression

An 82-word verbose prompt (filler phrases + duplicated sentences) sent with
`"compress": true`: **72 of ~109 estimated tokens removed (66% reduction)**.
The benchmark prompts are short (< 40-word compression floor), so compression
contributed ~0 above — it targets long, redundant prompts specifically.

## Benchmark-validity note (methodology)

The first version of this benchmark generated "unique" prompts from a single
template ("Unique question N: summarize the number 0.83..."), which are unique
as strings but nearly identical to the embedder — they matched *each other*
in the semantic cache and inflated the hit rate. Caught because the semantic
hit share was implausibly high, fixed by generating unique prompts from ~10⁹
qualifier×topic×shape combinations, and the repetitive run was relabeled as
the upper bound. Lesson: validate that benchmark inputs measure what you
think they measure.

---

## Filled résumé bullets

- **Efficiency:** Built a distributed LLM gateway that avoided **~43% of
  provider spend and ~41% of tokens** on a realistic mostly-unique workload
  (up to 98–99% on repetitive traffic) and served warm cached responses in
  **16 ms median**, by layering exact-match deduplication, semantic caching
  (embeddings + vector DB) and prompt compression — measured across **140K+
  benchmarked requests**.
- **Distributed systems:** Scaled horizontally behind a load balancer with
  shared cache and vector state across stateless instances, single-flight
  coalescing collapsing 20 concurrent duplicate misses into 1–2 provider
  calls, and atomic Redis-backed rate limiting enforcing per-key quotas
  exactly (10/10 at 10 rpm) across all nodes.
- **Centralized control:** Server-side provider-key storage with scoped
  revocable per-app keys, org-wide spend caps with per-team attribution, and
  full cost/latency observability via Prometheus + Grafana.
- **Reliability:** Multi-provider fallback with circuit breaking, SSE
  streaming passthrough, and semaphore backpressure that converted 100% of
  overload into clean 503s (zero unexpected failures), validated under k6 at
  up to **1,500 req/s** (cache-saturation stress) and **513 ms p99**.

## Reproduce

```powershell
# Primary benchmark (Run A)
docker compose down -v; docker compose up -d
docker run --rm --network llmopsgateway_default -v "${PWD}\k6:/scripts" `
  -e BASE=http://nginx:80 -e VUS=150 -e DURATION=7m grafana/k6 run /scripts/load_test.js
curl http://localhost:8080/admin/stats

# Baseline (Run B)
docker compose down -v
$env:EXACT_CACHE_ENABLED="false"; $env:SEMANTIC_CACHE_ENABLED="false"; $env:COMPRESSION_ENABLED="false"
docker compose up -d
docker run --rm --network llmopsgateway_default -v "${PWD}\k6:/scripts" `
  -e BASE=http://nginx:80 -e VUS=150 -e DURATION=3m grafana/k6 run /scripts/load_test.js
curl http://localhost:8080/admin/stats
Remove-Item Env:EXACT_CACHE_ENABLED, Env:SEMANTIC_CACHE_ENABLED, Env:COMPRESSION_ENABLED

# Checks
python scripts/distributed_check.py
python scripts/threshold_eval.py
```
