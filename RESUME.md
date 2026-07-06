# Résumé Bullets — Distributed LLM Gateway

All bullets follow the **XYZ format** — *Accomplished [X], as measured by [Y],
by doing [Z]* — and use only numbers actually measured in this repo
([RESULTS.md](RESULTS.md)). Primary numbers come from the **realistic
mostly-unique workload benchmark** (141,885 requests); the repetitive
stress run (1.34M requests, ~1,500 req/s, 99% hit rate) is always labeled as
the upper bound when quoted. Pick 3–5 per application; never use two bullets
that lead with the same metric. Suggested header:

> **Distributed LLM Gateway** — Java, Spring Boot, Redis, Qdrant, Docker, Nginx, Prometheus/Grafana, k6

---

## Headline bullets (the strongest 5 — default picks)

1. Avoided **43% of LLM provider spend and 41% of token usage** on a realistic
   mostly-unique workload — measured across **140K+ benchmarked requests** —
   by layering exact-match deduplication, semantic caching (embeddings +
   vector DB), and heuristic prompt compression in a Spring Boot gateway.

2. Served warm cached responses in **16 ms median vs 200 ms+ provider calls**,
   and stress-tested the gateway hot path to **~1,500 req/s at 513 ms p99**
   across 3 stateless replicas behind Nginx, validated with 1.3M+ k6 requests.

3. Eliminated **95% of redundant provider calls under thundering-herd load**
   (20 concurrent identical requests → 1 API call) by implementing
   cross-instance single-flight request coalescing with Redis `SET NX` locks
   and shared result keys.

4. Enforced per-application quotas **exactly (10/10 accepted at 10 rpm) across
   all cluster nodes** by building distributed rate limiting with atomic
   Redis Lua scripts instead of in-process limiters.

5. Achieved a **47% cache hit rate on mostly-unique traffic** (up to 99% on
   repetitive workloads) with **zero false hits**, by tuning the
   semantic-similarity threshold on a labeled paraphrase dataset — selected
   0.66 for 62% recall at 0% false-positive rate, prioritizing precision to
   never serve a wrong cached answer.

## Cost efficiency & caching

6. Cut per-request provider cost **21.6%** against an optimizations-off
   baseline on identical traffic (42.6% of potential spend avoided within the
   run), by benchmarking A/B with atomic per-team Redis cost counters.

7. Avoided **3.5M provider tokens in a single 7-minute benchmark** by serving
   66K of 141K requests from a Redis exact-match cache and a Qdrant semantic
   cache shared by all gateway instances.

8. Answered **reworded queries without new API calls** (7.6% of realistic
   traffic, 30%+ on paraphrase-heavy workloads) by embedding prompts into
   384-dim vectors and serving cached responses on cosine-similarity matches
   above a data-tuned threshold in Qdrant.

9. Cut verbose prompt sizes by **66%** while preserving meaning by building a
   token-importance compressor (duplicate-sentence removal, filler-phrase and
   stopword stripping) applied only above a configurable length floor.

10. Prevented stale or wrong cached answers at **0% false-hit rate** by
    designing a cacheability policy — TTL expiry, per-request opt-out, and
    automatic cache bypass for high-temperature (creative) requests.

11. Built a **zero-dependency local embedding** (feature-hashed stemmed
    words + bigrams, L2-normalized) enabling fully offline semantic caching
    within a 512MB memory budget, swappable to hosted neural embeddings via
    one config flag.

## Distributed systems

12. Guaranteed cache coherence across a 3-node cluster — a response cached by
    any instance served by every other (**6/6 cross-instance hits** in
    verification) — by keeping gateway replicas fully stateless with Redis
    and Qdrant as the only state stores.

13. Collapsed duplicate concurrent work across nodes into **one provider call
    per unique request** by implementing leader election per request-hash
    with TTL'd Redis locks, follower polling, and automatic leader takeover
    on crash.

14. Kept org-wide spend counters **consistent under 1.3M+ concurrent updates**
    from 3 nodes by using atomic Redis INCRBYFLOAT with month-keyed
    org/team/key counters instead of read-modify-write logic.

15. Closed a counter-leak race condition in distributed rate limiting by
    combining INCR + EXPIRE into a **single atomic Lua script** executed
    inside Redis.

16. Proved distributed correctness instead of claiming it by writing an
    automated verification suite (shared cache, coalescing, aggregate rate
    limits) that passes **3/3 against the live multi-node cluster**.

## Reliability & performance

17. Sustained **0% unexpected failures under overload** — 100% of excess
    load (1,200–20,000 requests per run) rejected as clean, immediate 503s —
    by bounding in-flight work per instance with semaphore-based backpressure
    instead of unbounded queues.

18. Maintained availability through provider outages by implementing ordered
    multi-provider failover (Groq → Gemini → fallback) with per-provider
    circuit breakers that open after 3 consecutive failures and half-open
    probe after a 30 s cooldown.

19. Delivered real-time token streaming with full observability by building
    SSE passthrough that forwards provider deltas as they arrive while
    accumulating the complete response for caching, cost accounting, and
    metrics.

20. Reduced cached-path latency **12×+ below provider baseline** (16 ms
    median vs 200 ms+ calls) by ordering the request pipeline
    cheapest-check-first: rate limit → backpressure → exact cache → semantic
    cache → provider.

## Platform, control plane & observability

21. Centralized LLM API-key custody for all client applications by keeping
    provider keys server-side only and issuing **scoped, revocable per-app
    keys** (with per-key rate limits and budgets) via a Redis-backed admin
    API.

22. Enforced organization-wide monthly spend caps with **per-team cost
    attribution** by computing per-call cost from token usage × configurable
    per-model pricing and rejecting over-budget calls with HTTP 402.

23. Built end-to-end observability for cost, latency percentiles, hit rates,
    rejections, and coalescing across all nodes by exposing Micrometer →
    Prometheus metrics with a **provisioned Grafana dashboard** (p50/p95/p99
    via histogram buckets).

24. Enabled zero-cost, zero-signup demos and **$0 load testing at 1.3M+
    cumulative requests** by designing a deterministic mock provider as the
    final failover target, exercising every production code path without
    API keys.

25. Shipped a reproducible one-command environment — Nginx LB, 3 gateway
    replicas, Redis, Qdrant, Prometheus, Grafana — via docker-compose, so the
    full distributed cluster starts with a single command on any machine.

## AI/ML engineering (for AI-engineer roles)

26. Tuned the semantic cache's precision/recall trade-off empirically by
    building a **labeled evaluation set (32 paraphrase/non-paraphrase pairs)**
    and a threshold-sweep tool reporting hit-rate vs false-hit-rate per
    threshold — a reproducible, data-justified model decision.

27. Designed an embedding abstraction supporting interchangeable vector
    models (local feature-hashing ↔ Gemini text-embedding-004) with
    re-runnable threshold recalibration, so upgrading the model is a config
    change, not a rewrite.

28. Implemented OpenAI-compatible chat-completions API (JSON + SSE streaming)
    so existing LLM client SDKs point at the gateway **with zero code
    changes**, including per-request cache/compression controls.

29. Caught and fixed a **benchmark-validity bug** — template-generated "unique"
    prompts were embedding near-identically and inflating semantic hit rate to
    99% — by detecting the implausible metric, regenerating prompts from a
    ~10⁹-combination space, and re-labeling the repetitive run as an upper
    bound.

30. Benchmarked honestly: isolated gateway performance from provider latency
    with a fixed-latency simulated provider, quantified **workload
    sensitivity** (47% hit rate on mostly-unique traffic vs 99% on
    repetitive), and documented all traffic-mix assumptions.

---

## One-liner (for the résumé header or a compact projects section)

> Built a distributed LLM gateway (Java/Spring Boot, Redis, Qdrant) that
> avoided ~43% of provider spend via semantic caching and request coalescing
> on realistic traffic, stress-tested to ~1,500 req/s across 3 load-balanced
> stateless nodes, with org-wide budgets and distributed rate limits —
> validated with 1.3M+ k6 requests.

## Usage tips

- **SDE roles:** lead with 2, 3, 4, 12, 17 (distributed systems + reliability).
- **AI-engineer roles:** lead with 1, 5, 26, 29, 30 (caching + ML evaluation +
  benchmark rigor — 29 and 30 are strong "measurement maturity" signals).
- **Generalist/startup:** 1, 2, 21, 24, 25 (impact + ownership + shipping).
- Keep every number defensible — each maps to RESULTS.md, and ARCHITECTURE.md
  Q17 has the "could these numbers mislead?" answer ready.
- When quoting the big numbers (1,500 req/s, 99% hit rate, 1.3M requests),
  always attach the "repetitive/stress workload" qualifier — the realistic-mix
  numbers (47% / 43%) are the ones to state unqualified.
- If a bullet feels long for your template, cut the *mechanism*, never the
  *metric*: "Avoided 43% of LLM provider spend across 140K+ benchmarked
  requests via layered exact + semantic caching" still works.
