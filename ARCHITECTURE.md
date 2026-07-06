# Architecture & Design Decisions

This document explains every significant architectural and design decision in
this project — what was chosen, **why**, what the alternatives were, and what
trade-offs were accepted — followed by **7 general warm-up questions** (for
interviewers who don't know the project) and the **top 20 technical
questions**, all with model answers. Written to be defensible in an interview for
an SDE / AI-engineer role.

Measured numbers referenced throughout come from [RESULTS.md](RESULTS.md).
Primary (realistic mostly-unique workload): 141,885 benchmarked requests,
47.1% cache hit rate, ~43% provider spend avoided, 16 ms median warm-cache
latency. Upper bound (near-fully-repetitive stress run): 1.34M requests,
~1,500 req/s, p99 513 ms, 99% hit rate — always quoted with that qualifier.

---

## 1. The elevator pitch (30 seconds)

> Organizations have many apps all calling LLM APIs directly. That duplicates
> spend, scatters API keys, and makes org-wide budgets and rate limits
> impossible — no single app can see total traffic. I built a **distributed
> LLM gateway**: a horizontally-scaled Spring Boot proxy that all LLM traffic
> flows through. For every request it **first tries to skip the provider call**
> (exact-match + semantic caching, single-flight coalescing), **shrinks the
> calls it can't skip** (prompt compression), and **centrally controls and
> observes everything** (scoped API keys, spend caps, distributed rate limits,
> Prometheus/Grafana). It runs as three stateless replicas behind Nginx with
> all shared state in Redis and Qdrant. On a realistic mostly-unique workload
> it avoided ~43% of provider spend at a 47% hit rate, and I validated the
> distributed behavior with 1.3M+ cumulative requests of k6 load testing.

## 2. System overview

```
clients ──> Nginx (round-robin LB)
              ├─> gateway1 ─┐        stateless Spring Boot replicas
              ├─> gateway2 ─┼──> Redis   — exact cache, rate-limit counters, spend
              └─> gateway3 ─┘            counters, single-flight locks, API keys,
                            │            shared live stats
                            ├──> Qdrant  — semantic-cache vectors (cosine search)
                            └──> Providers: Groq → Gemini → mock
                                 (ordered failover, per-provider circuit breakers)

Prometheus scrapes /actuator/prometheus on each replica ──> Grafana dashboard
```

**Request pipeline** (one method chain in `ChatService`, in this order):

```
auth (API-key filter)
 → distributed rate limit        (Redis, atomic, holds across instances)
 → backpressure                  (semaphore; full ⇒ clean 503)
 → prompt compression            (heuristic trimmer, toggleable)
 → exact cache lookup            (SHA-256 → Redis)
 → semantic cache lookup         (embedding → Qdrant cosine search ≥ threshold)
 → budget check                  (org + per-key monthly caps ⇒ 402)
 → single-flight coalescing      (Redis lock; one provider call per unique request)
 → provider failover             (circuit breakers; groq → gemini → mock)
 → store caches, record spend, publish metrics
```

The ordering itself is a design decision: **cheapest checks first**. Rate
limiting and backpressure cost one Redis op / one semaphore try, so they run
before any expensive work. The exact cache (one Redis GET) runs before the
semantic cache (an embedding + a vector search). Budget is only checked when
we're actually about to spend money — cache hits are free, so they shouldn't
be blocked by an exhausted budget.

## 3. Why this project is shaped the way it is

### 3.1 Why a centralized gateway at all (and not a per-app library)?

A caching library inside each app would get you caching. It structurally
**cannot** get you:

- **Org-wide spend caps** — no single app sees total spend. The gateway
  tracks org/team/key spend in shared atomic counters and rejects with 402
  when a cap is hit. This is the single strongest justification.
- **Global rate limiting** — providers rate-limit your whole organization;
  only a point that sees all traffic can throttle under that shared ceiling.
- **Central key custody** — real provider keys live only server-side; apps
  get scoped, revocable internal keys. Rotating a provider key touches one
  place instead of every app.
- **One observability plane** — every call's cost/latency/tokens in one
  dashboard, attributable per team.
- **Single point of policy** — switching providers is one config change.

Caching *rides on top* of that centralization; it isn't the reason for it.

### 3.2 Why distributed (multiple replicas)?

Once every app's LLM traffic flows through one service, that service is
shared critical infrastructure on the request path:

1. **Throughput** — one instance has a ceiling (here: 64 concurrent in-flight
   requests; at 200 ms/provider-call that's ~320 req/s of misses per node).
2. **Availability** — one instance dying would cut off every app at once.
3. **Zero-downtime deploys** — replicas can roll one at a time.

The **key engineering consequence**: replicas can't share in-memory state, so
gateway instances are **stateless** and every piece of shared state — cache
entries, rate-limit counters, budgets, coalescing locks, API keys, live
stats — lives in stores every instance reaches (Redis, Qdrant). This is
demonstrated, not just claimed: `scripts/distributed_check.py` proves a
response cached via one instance is served by the others, 20 concurrent
identical misses across instances produce 1 provider call, and a 10-rpm key
gets exactly 10 requests through the whole cluster.

**Honest scope**: at this project's scale a single instance would suffice.
Distribution is built and validated to demonstrate the capability, on a local
cluster — I don't claim production traffic.

## 4. Design decisions, one by one

Each entry: **decision → why → alternatives → trade-off accepted.**

### D1. Java + Spring Boot (MVC, not WebFlux)

- **Why:** Spring Boot gives auth filters, config binding, Actuator/Micrometer
  metrics, and Redis integration out of the box; it's also the stack I'd most
  likely use at a Java shop. I chose **Spring MVC over WebFlux** because
  virtual threads (Java 21, `spring.threads.virtual.enabled=true`) give me
  cheap blocking concurrency — I can write simple blocking code (Redis call,
  HTTP call, `Thread.sleep` polling) and still handle hundreds of concurrent
  requests, without the cognitive cost of reactive streams.
- **Alternatives:** WebFlux (reactive) — better for extreme fan-out, but the
  code becomes much harder to read and debug for no measurable benefit at
  this scale. Node/Python — fine, but weaker story for typed, threaded
  distributed coordination.
- **Trade-off:** MVC + virtual threads is newer and less battle-tested than
  reactive under very high connection counts; 1,500 req/s measured on the
  cache-saturation stress run says it's more than enough here.

### D2. Redis as the coordination backbone

- **Why:** Almost every distributed need here reduces to "small shared
  mutable state with atomic updates": rate-limit counters (`INCR`), spend
  accounting (`INCRBYFLOAT`), locks (`SET NX EX`), the exact cache
  (`SET`/`GET` with TTL), key storage (hashes), live stats (one hash). Redis
  does all of these atomically at sub-millisecond latency with one dependency.
- **Alternatives:** a SQL database (heavier, slower for counters, overkill —
  there's no relational data here); Zookeeper/etcd for locks (much heavier
  operationally, designed for coordination-critical systems); Hazelcast /
  embedded data grids (breaks the "instances are stateless" property).
- **Trade-off:** Redis is a single point of failure in my compose file. In
  production you'd run Redis Sentinel/Cluster; for this project the honest
  answer is "the pattern is right, the deployment is minimal."

### D3. Qdrant as the vector database (via plain REST)

- **Why:** the semantic cache needs approximate nearest-neighbour search over
  embedding vectors — a specialized index (HNSW) that Redis/SQL don't give you
  out of the box. Qdrant is open-source, runs as one small container, and has
  a simple REST API. I deliberately **skipped the Java client library** and
  call the REST API with Spring's `RestClient` — three endpoints (create
  collection, upsert point, search) don't justify a dependency.
- **Alternatives:** pgvector (would add Postgres just for this), Chroma
  (similar; Qdrant's API was simpler), Redis' own vector search (ties two
  concerns to one store and needs the Redis Stack modules), in-memory brute
  force (breaks cross-instance sharing).
- **Trade-off:** one more moving part in the cluster; accepted because the
  semantic cache is the centerpiece feature.

### D4. Two-tier caching: exact match first, then semantic

- **Why:** the two tiers have very different costs and confidence. The exact
  cache is one SHA-256 + one Redis GET — microseconds, zero false positives —
  so it runs first and catches byte-identical repeats. The semantic tier
  (embed + vector search) is slower and probabilistic, and only runs on exact
  misses. In the realistic-mix load test this split was visible: 39.6% exact,
  7.6% semantic (on paraphrase-heavy traffic the semantic share rises to 30%+).
- **Key detail:** the exact-cache key is a SHA-256 of the *canonicalized*
  request — role:content of every message joined with a separator, plus the
  temperature. Hashing gives fixed-size keys and avoids storing prompts as
  Redis keys.
- **Alternatives:** semantic-only (wastes embedding work on identical
  repeats), normalizing text before hashing (lowering/stripping whitespace —
  rejected to keep "exact" semantics truly exact; near-duplicates are the
  semantic tier's job).
- **Trade-off:** two lookups on a full miss. Microseconds vs a 200 ms+
  provider call — trivially worth it.

### D5. Cacheability policy: skip caching for high-temperature requests

- **Why:** temperature is the "creativity knob". A user asking for creative
  output (temperature > 0.7) expects *different* answers per call; serving a
  cached one would be wrong. So such requests bypass both cache reads *and*
  writes. Clients can also opt out per-request with `"cache": false`.
- **Trade-off:** lower hit rate on creative workloads — correct behavior
  beats hit rate.

### D6. Embeddings behind a two-mode service (local hash / hosted Gemini)

- **Why:** the honest constraint was "everything must run free and locally".
  Mode 1 (`hash`, default) is a **zero-dependency local embedding**: content
  words (crudely stemmed, stopwords dropped) and word bigrams are
  feature-hashed into a 384-dim vector with a sign trick, then L2-normalized.
  It's not a neural embedding, but reworded queries share content words, so
  cosine similarity separates paraphrases from unrelated queries well enough
  (62% hit / 0% false-hit at the chosen threshold). Mode 2 (`gemini`) swaps in
  Google's free `text-embedding-004` for true semantic vectors — one config
  change, because callers only see `embed()` / `similarity()`.
- **Alternatives:** ONNX MiniLM in-process (real neural embeddings locally,
  but a model download, native runtime, and ~100MB+ memory on a 512MB free
  deploy tier); calling the LLM to judge similarity (absurdly expensive —
  defeats the purpose).
- **Trade-off:** hash embeddings miss paraphrases with low word overlap
  ("capital of France" vs "which city is France's capital" scores 0.61,
  below the 0.66 threshold). Accepted, measured, documented — and the
  threshold-eval script exists precisely to re-tune when swapping embedders.

### D7. Data-justified similarity threshold (0.66)

- **Why:** the threshold is the precision/recall dial of the semantic cache,
  and a wrong answer served confidently (false hit) is much worse than a
  miss. Instead of guessing, `scripts/threshold_eval.py` sweeps thresholds
  over 32 labeled pairs (16 equivalent, 16 not) *through the gateway's own
  embedding endpoint*, and prints hit-rate vs false-hit-rate. I picked
  **0.66: 62% hit rate at 0% false hits** — the highest recall with zero
  false positives — over 0.60 (81% hit but 6% false hits).
- **Interview point:** this is a small ML-evaluation loop — labeled data,
  a metric trade-off (precision over recall), and a documented, reproducible
  choice.

### D8. Single-flight coalescing via a Redis lock + polled result

- **What:** on a cache miss, `SET sf:lock:{hash} NX EX 30`. The winner
  (leader) calls the provider, writes the result to `sf:result:{hash}`
  (TTL 60 s), releases the lock. Losers poll the result key every 100 ms; if
  the lock vanishes without a result (leader crashed/failed), a waiter
  acquires the lock and takes over. After ~15 s of waiting, a waiter gives up
  and calls the provider itself.
- **Why:** the classic thundering-herd problem — a burst of identical
  requests against a cold cache would trigger N identical provider calls.
  Because the lock is in Redis, coalescing works **across instances**, which
  in-process solutions can't do. Measured: 20 concurrent identical requests
  → 1 provider call.
- **Alternatives:** Redis pub/sub to notify waiters (more elegant, less
  latency than polling, but more code and a subscription lifecycle to manage
  — polling at 100 ms is simpler and the latency is bounded and small);
  in-process `ConcurrentHashMap` of futures (correct per-instance, useless
  across instances); Redisson's distributed locks (a library for what's
  effectively 5 lines of Redis).
- **Trade-offs accepted:** up to ~100 ms extra latency for coalesced waiters;
  lock TTL (30 s) as the crash-recovery mechanism — if a leader dies, waiters
  recover after the TTL rather than instantly. Both fine for the happy path.

### D9. Distributed rate limiting: fixed window with an atomic Lua script

- **What:** one Redis counter per (key, minute): `INCR rl:{key}:{epochMinute}`,
  with `EXPIRE` set atomically on first increment via a 3-line Lua script;
  reject with 429 once the count exceeds the key's rpm.
- **Why Lua:** `INCR` then `EXPIRE` as two calls has a race — if the process
  dies between them the counter never expires. Lua executes atomically inside
  Redis, closing that gap for 3 lines of code.
- **Why fixed window:** it's the simplest algorithm that holds **in aggregate
  across instances** (the counter lives in Redis, not in memory). Measured:
  a 10-rpm key got exactly 10 requests through a 30-request burst across
  3 instances.
- **Alternatives:** sliding-window log (a Redis ZSET of timestamps — more
  precise, more memory, more code); token bucket (smoother, allows
  controlled bursts, needs stored refill state); Resilience4j (in-process
  only — **doesn't work across instances**, which is disqualifying here).
- **Trade-off:** the classic fixed-window edge — up to 2× the limit can pass
  straddling a window boundary (10 requests at 11:59:59 + 10 at 12:00:01).
  Known, accepted, and the first thing I'd upgrade (to sliding window) if
  precision mattered.

### D10. Spend caps with atomic Redis counters (checked before the call)

- **What:** cost = tokens × per-model price (config, per 1M tokens). Every
  real provider call does `INCRBYFLOAT` on three monthly counters —
  `spend:org:{month}`, `spend:team:{team}:{month}`, `spend:key:{key}:{month}`
  — and before any call the org cap and per-key cap are checked (402 on
  exhaustion, or warn-only if `enforce=false`).
- **Why:** `INCRBYFLOAT` is atomic, so concurrent updates from all instances
  never lose increments — a read-modify-write in app code would. Month-keyed
  counters give per-month budgets and attribution with zero scheduled jobs.
- **Trade-off:** check-then-spend isn't transactional — N in-flight requests
  can each pass the check just before the cap and overshoot it by a few
  calls' cost. For budget enforcement (unlike, say, bank balances) slight
  overshoot is acceptable; making it strict would need a Lua
  check-and-reserve script — I can describe that upgrade on request.

### D11. Provider abstraction: one interface, three implementations, ordered failover

- **What:** `LlmProvider` (name / configured / complete / stream) implemented
  by Groq, Gemini, and a mock. `ProviderRouter` walks the configured order,
  skipping unconfigured providers and providers with an open circuit breaker,
  and returns the first success.
- **Why an interface *here* (when I inlined interfaces elsewhere):** this is
  genuine polymorphism — three real implementations chosen at runtime. The
  rule I applied across the codebase: **an interface with one implementation
  is indirection; with three, it's design.**
- **Why not Spring AI / LangChain4j:** each provider adapter is ~60 lines of
  "build JSON, POST, parse JSON". A framework would add a large dependency,
  version risk (Spring Boot 4 was brand new), and an abstraction I'd then
  have to work around for gateway-specific needs (raw SSE passthrough,
  token accounting). Writing the two adapters by hand was less total effort.

### D12. Circuit breaker: minimal, in-memory, per instance

- **What:** per provider — 3 consecutive failures open the circuit for 30 s;
  after cooldown exactly one probe is let through (half-open via a CAS), and
  a success closes it. ~40 lines, nested inside the router.
- **Why:** without it, a dead provider adds its full timeout to every request
  before failover. With it, a persistently failing provider is skipped
  immediately and re-probed periodically.
- **Why in-memory (not Redis) when everything else is shared:** provider
  health is an *observation*, not shared *state that must be consistent*.
  Each instance learning independently costs at most (threshold × instances)
  extra failures — 9 requests cluster-wide — before all circuits open.
  Sharing it would add Redis round-trips to the hot path for negligible
  benefit. Knowing **which state must be shared and which needn't be** is
  the design point.
- **Alternatives:** Resilience4j — fine, but it's a dependency for 40 lines,
  and being explicit made the mechanism explainable.

### D13. Backpressure: a bounded semaphore, reject-fast with 503

- **What:** a `Semaphore(64)` per instance wraps the pipeline;
  `tryAcquire()` failure → immediate 503 "overloaded". No queue.
- **Why:** unbounded concurrency collapses a service under overload (memory,
  connection pools, timeout cascades). Bounding in-flight work and **failing
  fast** keeps latency sane for the requests you do accept and gives clients
  an actionable signal (retry later / another instance). Under the 1,500
  req/s stress run this rejected 1.5% of requests cleanly while everything
  accepted stayed within p99 513 ms; on the realistic run it converted 100%
  of overload (1,195 requests) into clean 503s — the mechanism visibly
  working in both regimes.
- **Alternatives:** a bounded queue in front (adds latency and hides
  overload), thread-pool sizing as implicit backpressure (opaque, and
  virtual threads make "pool size" meaningless as a limit).
- **Why per-instance (not shared):** total capacity = per-instance bound ×
  instances, which scales exactly as replicas are added — sharing it would
  be both slower and wrong.

### D14. SSE streaming passthrough (with cache replay)

- **What:** `stream: true` returns Server-Sent Events in OpenAI's chunk
  format. Groq streams for real (line-by-line passthrough while the full
  text accumulates for caching/cost); Gemini/mock "fake-stream" via a default
  interface method (complete, then emit). Cache **hits** on streaming
  requests replay the stored text in chunks. A final custom event carries
  usage + gateway metadata, then `[DONE]`.
- **Why SSE (not WebSockets):** the flow is strictly one-way
  server-to-client, SSE is plain HTTP (works through Nginx with buffering
  off), and it's what OpenAI-compatible clients already speak.
- **Design point:** streaming *and* observability usually conflict — you
  can't know tokens/cost until the stream ends. Accumulating while
  forwarding gives both.
- **Trade-off:** streaming misses skip coalescing (a leader can't easily
  re-stream to followers). Streaming cache hits still work. Documented
  simplification.

### D15. Stateless instances; every instance identical

- **What:** no session state, no sticky sessions, no leader election. Any
  instance can serve any request; Nginx round-robins blindly. The demo UI
  displays which instance served each request to make this visible.
- **Why:** statelessness is what makes horizontal scaling trivial — add a
  replica, add capacity; kill a replica, lose nothing. It's the single most
  load-bearing property in the design; everything shared was pushed into
  Redis/Qdrant to preserve it.

### D16. Nginx as the load balancer

- **Why:** boring, ubiquitous, 20 lines of config. Round-robin is exactly
  what stateless replicas want. `proxy_buffering off` is the one non-default
  — SSE dies behind a buffering proxy.
- **Alternatives:** HAProxy/Traefik/Envoy (equivalent here, more concepts),
  Kubernetes + a Service (an entire orchestrator for a demo — compose is
  understandable at a glance).

### D17. Mock provider as a first-class citizen

- **Why:** three jobs. (1) **Keyless demo** — clone the repo, `docker compose
  up`, everything works with zero signups. (2) **Free load testing** — the
  1.34M-request benchmark costs $0 and burns no rate-limited free tiers,
  while exercising every real code path (pricing applied, caches written,
  metrics published) — only the outbound HTTP call is simulated (200 ms
  sleep). (3) **Failover demo** — as the last provider in the order, it
  makes circuit-breaking visible without real outages.
- **Why this is honest benchmarking:** the gateway's own performance
  (caching, coordination, routing) is what's being measured; provider
  latency is not the system under test. RESULTS.md states this framing
  explicitly.

### D18. Observability: Micrometer/Prometheus + a Redis stats hash (dual-published)

- **What:** every event goes to two places from one `GatewayMetrics` class —
  Micrometer counters/timers (per instance, scraped by Prometheus, aggregated
  in a provisioned Grafana dashboard, with p50/95/99 from histogram buckets)
  and a single shared Redis hash (cluster-wide totals the demo UI polls).
- **Why both:** they answer different questions. Prometheus = time series,
  rates, percentiles, per-instance breakdowns (the engineering view).
  The Redis hash = instantly consistent cluster-wide totals for a UI that
  must show identical numbers no matter which instance serves the page
  (the demo view). Deriving the latter from Prometheus would need a query
  API dependency in the UI path.
- **Detail worth mentioning:** latency histograms are enabled in code via a
  `MeterFilter` (percentiles 0.5/0.95/0.99) rather than YAML — version-proof
  against Boot property renames.

### D19. Configuration over hardcoding — one `@ConfigurationProperties` record

- **What:** every threshold, TTL, budget, price, provider order and toggle
  lives in one immutable record tree (`GatewayProperties`) bound from
  `application.yml`, each field env-overridable (`${SEMANTIC_THRESHOLD:0.66}`)
  so compose can tune the cluster without rebuilds.
- **Why records:** immutable, constructor-bound, ~100 lines instead of ~300
  lines of getter/setter boilerplate; typos in YAML fail fast at startup
  instead of silently defaulting.

### D20. Deliberate simplicity — what I intentionally did NOT build

Signals judgment, not laziness; each has a "when I'd add it":

- **No Kubernetes** — compose demonstrates multi-instance behavior with a
  fraction of the concepts. Add when: real deploys, autoscaling.
- **No message queue** — the request path is synchronous request/response;
  a queue adds latency and machinery with nothing to decouple. Add when:
  async jobs (batch embedding backfills, usage exports).
- **No microservices** — one service with clear packages. Splitting a
  ~1,750-line codebase would be architecture theater.
- **No auth framework (JWT/OAuth)** — opaque keys in Redis are exactly the
  scoped-revocable model needed; Spring Security would triple auth code. Add
  when: multi-tenant identity, SSO.
- **No exhaustive test pyramid** — the brief calls for minimal tests; the
  high-value validation is the *distributed checks script* + load test,
  which prove the cross-instance properties that unit tests can't.
- **Thin defensive coding** — happy-path code with failures surfacing as
  clean HTTP errors; ~20 try/catch blocks were deliberately removed in a
  cleanup pass. In production I'd add: retries with jitter, timeouts
  everywhere, graceful semantic-cache degradation when Qdrant is down.

## 5. Distributed-correctness cheat sheet

The four properties that must hold **across instances**, how, and the proof:

| Property | Mechanism | Proof (distributed_check.py / k6) |
|---|---|---|
| Shared cache | Redis + Qdrant hold entries; instances stateless | miss on gw3 → 6/6 exact hits from gw1/gw2/gw3 |
| Single provider call under concurrent duplicates | Redis `SET NX` lock + shared result key | 20 concurrent identical → 1 provider call |
| Rate limits hold in aggregate | atomic Lua `INCR` on shared counter | 10-rpm key → exactly 10/30 accepted cluster-wide |
| Spend counters consistent under concurrency | Redis `INCRBYFLOAT` (atomic) | totals consistent across 1.32M concurrent requests |

## 6. Known limitations (say them before the interviewer does)

1. **Fixed-window rate limiting** allows ≤2× burst at window boundaries.
2. **Budget check-then-spend race** can overshoot caps by a few in-flight calls.
3. **Hash embeddings** miss low-word-overlap paraphrases (0% false hits, but
   only 62% paraphrase recall; swap to Gemini embeddings for better recall).
4. **Redis is a SPOF** in the compose cluster (pattern right, deployment minimal).
5. **Semantic-cache invalidation is TTL-only** — a stale-but-similar answer
   can be served until expiry.
6. **Streaming misses aren't coalesced.**
7. **Cache entries aren't isolated per key/team** — fine for one org (sharing
   is the point), would need namespacing for multi-tenant use.
8. **Circuit breaker is per-instance** — deliberate (see D12), but worth
   naming as a choice.

---

## 7. Interview questions & answers

### 7a. General warm-up questions (asked by anyone — HR, managers, or interviewers who haven't seen the project)

These come first in almost every interview. Keep the answers conversational —
no jargon until they ask for it.

### G1. Tell me about this project in simple terms. What does it do?

> Companies today have lots of apps that all call AI services like ChatGPT-style
> APIs. Each of those calls costs money, and when every app calls the AI
> provider directly, three problems appear: the company pays repeatedly for
> the same questions, nobody can see or control the total spend, and secret
> API keys end up copied into every app. My project is a **gateway** — a
> middleman server that all those apps send their AI requests through instead.
> The gateway remembers previous answers, so if the same or a similar question
> was already asked by anyone, it returns the saved answer instantly and for
> free. If it must call the AI provider, it makes the request cheaper, checks
> it against the company's budget, and records what it cost. And it runs as
> several copies behind a load balancer so it's fast and doesn't have a single
> point of failure. In my benchmarks it avoided about 43% of provider spend
> on realistic mostly-unique traffic — and up to 98% when traffic is highly
> repetitive — across 1.3M+ total test requests.

### G2. Who are the users? Walk me through the flow with a concrete example.

> Two kinds of users. The **applications** are the main users — say a
> company's support chatbot, its document summarizer, and its internal search
> tool. Each gets its own gateway API key from the admin. The **platform/admin
> team** is the second user — they create and revoke those keys, set budgets
> like "support team: $50/month", and watch the dashboards.
>
> Concrete flow: a customer asks the support chatbot "how do I reset my
> password?". The chatbot sends that to the gateway with its API key. The
> gateway checks the key is valid and under its rate limit, then checks: has
> anyone asked this exact question before? Yes → return the saved answer in
> ~16 ms, cost $0. If it was worded differently — "how can I change my
> password?" — the semantic cache catches that it *means* the same thing and
> still serves the saved answer. Only a genuinely new question goes out to the
> AI provider (Groq or Gemini), and that answer is saved for next time, its
> cost recorded against the support team's budget. The demo UI shows all of
> this live — you can watch a question flip from "miss" to "cache hit".

### G3. What are the advantages? Why would a company use this?

> Four concrete ones. **Cost** — repeated and reworded questions are answered
> from cache instead of paid API calls; I measured ~43% of provider spend
> avoided on a realistic mostly-unique workload, rising toward ~98% the more
> repetitive the traffic. **Control** — one place to manage keys, enforce
> budgets ("the org spends at most $X/month"), and rate-limit each app; none
> of that is possible when apps call providers directly, because no single
> app sees the total. **Speed** — a cached answer returns in ~16 ms
> versus hundreds of ms to seconds from a real provider. **Reliability** — if
> one AI provider goes down, the gateway automatically fails over to another,
> and cached answers keep being served even during an outage. The one-line
> version: *cheaper, controlled, faster, and harder to break.*

### G4. What are the weaknesses of your project? What would you improve?

> I'll separate honest limitations from deliberate scope. Limitations: the
> semantic matching uses a lightweight local embedding, so it catches
> rewordings that share words but misses cleverly different phrasings — I
> measured 62% paraphrase recall (with zero wrong answers served, which I
> prioritized). Cached answers can be stale until their one-hour expiry —
> fine for factual Q&A, wrong for "what's the weather". Redis is a single
> point of failure in my local setup. And my rate limiter allows brief bursts
> at minute boundaries. Deliberate scope: it's benchmarked against a simulated
> provider (I measured *my* system, not Groq's), and it's demonstrated on a
> local cluster, not a cloud deployment. First improvements: swap in neural
> embeddings for better matching, make Redis highly available, and add
> per-team cache isolation for multi-tenant use.

### G5. Why did you build this? What did you learn?

> I wanted one project that forced me to touch both things I care about —
> distributed systems and the practical side of AI engineering — and LLM cost
> optimization sits exactly at that intersection; it's also a real problem
> companies are hiring people to solve right now. The biggest things I
> learned: first, *state placement is the whole game* in distributed design —
> the moment you run two copies of a service, every piece of state has to
> justify where it lives (I put rate limits and locks in Redis because they
> must be consistent, but kept circuit breakers in-memory because they
> don't). Second, measurement discipline — my first cost-reduction benchmark
> was invalid because the "off" configuration was silently ignored; I only
> caught it by testing the precondition. Third, evaluating an ML component
> properly — I tuned the similarity threshold with a labeled dataset and a
> precision/recall trade-off instead of guessing.

### G6. How long did it take, and what was the hardest part?

> The core build was compressed and iterative: backend, cluster, and UI
> first; then a measurement phase (1.3M-request load tests, threshold
> evaluation, a baseline comparison); then a deliberate simplification pass
> where I cut the codebase to ~29 classes / ~1,750 lines while keeping every
> feature working — deleting code without breaking distributed behavior was
> harder than writing it. The hardest single part was getting the
> cross-instance behaviors *provably* right: it's easy to claim "the cache is
> shared" and much more work to write checks that demonstrate one instance's
> cache hit being served by another, 20 concurrent duplicates collapsing to
> one provider call, and a rate limit holding exactly across three nodes.

### G7. Is it deployed? How would I try it?

> It runs anywhere with Docker: `docker compose up` starts the full cluster —
> load balancer, three gateway copies, Redis, the vector database, Prometheus
> and Grafana — with zero configuration and zero API keys needed, because a
> built-in mock provider stands in for the real AI. You open localhost:8080,
> type a prompt, and watch it come back as a miss, then a cache hit showing
> latency, cost saved, and which of the three instances served it. With free
> Groq/Gemini keys in a `.env` file it talks to real models. It's designed to
> also deploy as a single instance on a free cloud tier (Render) with hosted
> Redis/Qdrant free tiers.

---

### 7b. Top 20 technical questions & answers

### Q1. Walk me through what happens when a request hits your gateway.

> Nginx round-robins it to one of three identical Spring Boot instances. A
> filter authenticates the internal API key against Redis. Then the pipeline:
> distributed rate-limit check (atomic Redis counter — one op), backpressure
> (semaphore; full ⇒ 503), optional prompt compression, exact-cache lookup
> (SHA-256 of the canonicalized request → Redis GET), then semantic-cache
> lookup (embed the prompt, cosine search in Qdrant, serve if similarity ≥
> 0.66 and not expired). If both miss: budget check (402 if a monthly cap is
> exhausted), then single-flight — a Redis lock ensures only one concurrent
> caller per unique request actually calls a provider; the router tries
> providers in order with circuit breakers. The response is stored in both
> caches, spend is recorded atomically, metrics are published, and the client
> gets an OpenAI-style response plus a `gateway` block: cache status, latency,
> cost, which instance served it.

### Q2. Why does this need to be distributed? Isn't that overkill?

> For the current load, yes — one instance would suffice, and I say so in the
> README. But the *premise* is that this is the single path for an entire
> org's LLM traffic, which makes it shared critical infrastructure: it needs
> capacity beyond one box, it can't be a single point of failure, and it must
> deploy without downtime. The interesting engineering isn't running three
> copies — it's that the moment you run two, all state must leave process
> memory. Cache, rate limits, budgets, locks all moved to Redis/Qdrant, and I
> wrote a script that proves each property holds across instances rather than
> just claiming it.

### Q3. How does the semantic cache work, exactly?

> On a miss, the prompt is embedded into a vector and stored in Qdrant
> alongside the response. On a lookup, I embed the incoming prompt and do a
> cosine nearest-neighbour search; if the best match scores ≥ 0.66 and isn't
> past TTL, I serve its stored response without any provider call. That's how
> "Tell me about vector databases" gets answered from a cache entry written
> for "What is a vector database?" — measured at 7.6% of all requests on the
> realistic mostly-unique mix (30%+ on paraphrase-heavy traffic), on top of
> 39.6% exact hits.

### Q4. How did you pick the 0.66 threshold? What happens if it's wrong?

> Empirically. The threshold trades precision against recall: too low and you
> serve wrong answers (false hits — the worst failure mode), too high and the
> cache never fires. I built a small labeled dataset — 16 paraphrase pairs, 16
> lookalike-but-different pairs — and a script that sweeps thresholds through
> the gateway's own embedding endpoint, printing hit rate vs false-hit rate.
> 0.60 gave 81% hits but 6% false hits; **0.66 gave 62% hits with 0% false
> hits**, and I chose zero false positives because serving a confidently wrong
> answer breaks user trust in the whole cache. It's re-runnable in one command
> when the embedder changes.

### Q5. Explain single-flight coalescing. Why do you need it if you have a cache?

> The cache only helps *after* someone has populated it. If 20 identical
> requests arrive concurrently against a cold cache — a thundering herd —
> they'd all miss and trigger 20 identical provider calls. Single-flight
> collapses them: every request for the same hash races on a Redis `SET NX`
> lock; the winner calls the provider and writes the result to a shared Redis
> key; the other 19 poll that key and return the same answer. Because the
> lock is in Redis, it works even when the 20 requests land on different
> instances — that's the part an in-process solution can't do. Measured: 20
> concurrent identical requests → exactly 1 provider call.

### Q6. What if the single-flight leader crashes mid-call?

> Two safety nets. The lock has a 30-second TTL, so a crashed leader's lock
> evaporates. And waiters don't just poll the result key — they also watch the
> lock; if it disappears with no result, a waiter atomically grabs the lock
> and becomes the new leader. Worst case a waiter gives up after ~15 s and
> calls the provider itself. So the failure mode is degraded latency, never a
> stuck request.

### Q7. How does your rate limiting stay correct across three instances?

> The counter isn't in any instance — it's one Redis key per (API key,
> minute), incremented atomically. Whichever instance handles a request does
> `INCR rl:{key}:{minute}` and rejects with 429 if the result exceeds the
> key's limit. Since Redis serializes the increments, the limit holds in
> aggregate no matter how traffic is spread. The subtle bit: `INCR` and
> `EXPIRE` as two separate commands leave a race where a counter could live
> forever, so both run inside one 3-line Lua script, which Redis executes
> atomically. Verified: a 10-rpm key, 30 concurrent requests across the
> cluster → exactly 10 accepted.

### Q8. What are the weaknesses of your fixed-window rate limiter?

> Boundary bursts: a client can send its full quota at 11:59:59 and again at
> 12:00:01 — up to 2× the nominal rate across a window edge. I chose fixed
> window anyway because it's one counter and one atomic op, and the limit
> still holds per window across all instances, which was the property I
> needed to demonstrate. The upgrade path is a sliding-window log — a Redis
> ZSET of request timestamps, trimmed and counted in a Lua script — more
> precise, more memory, more code. I'd switch if precise limits were a
> product requirement.

### Q9. Why Redis for coordination and not a database, or Zookeeper?

> The workloads are all "tiny shared mutable state, atomic updates, high
> frequency": counters, locks, small hashes, TTL'd blobs. Redis does each of
> those natively, atomically, in sub-millisecond time, as one lightweight
> container. A SQL database adds schema/connection weight and is slower at
> exactly this; Zookeeper/etcd are consensus systems for coordination-critical
> control planes — far heavier than the problem. One nuance I can defend: I
> use Redis *data structures* for correctness (atomic INCR, SET NX), not
> Redis as a database of record — if it flushes, I lose cache entries and
> counters, not any irreplaceable data. Keys would be the exception, and in
> production I'd back them with persistence (AOF) or a real store.

### Q10. Why did you write your own embeddings instead of using a model?

> Constraint-driven: everything had to run free, offline, and inside a 512MB
> deploy target. A neural embedding model in-process meant a model download
> and native runtime; a hosted embedding API meant a key and rate limits. So
> the default is feature hashing — stemmed content words and bigrams hashed
> into a 384-dim signed vector, L2-normalized. It's the same trick as the
> "hashing trick" in classic ML, and for paraphrase detection it's decent
> because rewordings share content words. I measured it honestly: 62%
> paraphrase recall at 0% false hits. The seam matters though — it's behind a
> two-mode service, so switching to Gemini's free embedding API is one env
> var, and the threshold-eval script re-tunes the threshold for the new
> vector space.

### Q11. How do you calculate and enforce spending?

> Each provider response reports prompt and completion tokens; cost = tokens ×
> per-model prices from config (per 1M tokens, like real pricing pages). On
> every real call I atomically `INCRBYFLOAT` three month-keyed Redis counters
> — org, team, key — which gives attribution ("payments team spent $X this
> month") for free. Before any provider call I check the org cap and the
> key's cap and reject with 402 if exhausted; there's also a warn-only mode.
> Cache hits deliberately bypass the budget check — they cost nothing, and
> blocking free answers on an exhausted budget would be wrong. Known race:
> check-then-spend isn't transactional, so concurrent in-flight calls can
> overshoot a cap slightly; strict enforcement would be a Lua
> check-and-reserve, which I judged unnecessary for budget semantics.

### Q12. How does streaming work, and how do you cache a stream?

> The client gets Server-Sent Events in OpenAI's chunk format. For Groq I do
> true passthrough — read its SSE line by line, forward each delta
> immediately, and accumulate the full text in a buffer. When the stream
> ends, the accumulated text is what gets cached, costed, and counted —
> that's how streaming coexists with observability. If a *streaming* request
> hits the cache, I replay the stored text in chunks so the client
> experience is consistent. Two honest simplifications: providers without
> streaming APIs fake-stream (complete, then emit), and streaming misses skip
> coalescing.

### Q13. What happens when a provider goes down?

> Three layers. Per-request: an ordered failover list — if Groq errors, the
> same request immediately tries Gemini, then the mock. Across requests: a
> per-provider circuit breaker — three consecutive failures open the circuit
> and the router skips that provider for 30 s, then lets exactly one probe
> through (half-open, via a compare-and-set so only one thread probes).
> That converts "every request eats Groq's timeout" into "requests skip Groq
> instantly until it recovers". And the mock provider at the end of the chain
> means the demo literally cannot have zero providers. The breaker is
> in-memory per instance, deliberately: provider health is an observation,
> not state that needs consistency — worst case is 9 extra failures
> cluster-wide before all three instances learn, versus adding a Redis
> round-trip to every request.

### Q14. Why didn't you use Spring AI or LangChain to talk to the LLMs?

> Each adapter is ~60 lines: build a JSON map, POST with RestClient, read
> fields out of the response. A framework buys convenience but costs a heavy
> dependency, version coupling (Spring Boot 4 had just shipped — Spring AI
> compatibility was uncertain), and abstraction I'd have to fight for
> gateway-specific needs like raw SSE passthrough and token accounting. For
> two providers, hand-rolled was less total complexity. If I were integrating
> ten providers with tool-calling and structured output, I'd revisit — that's
> when the framework's surface pays for itself.

### Q15. How would you scale this 10× or 100×?

> 10× (≈15K req/s): mostly turning knobs — more gateway replicas (stateless,
> so linear), raise the backpressure bound, and watch Redis, which at ~a few
> ops per request would be approaching single-node limits: move to Redis
> Cluster and shard hot keys. Qdrant reads scale with replicas. 100×: the
> architecture changes — Redis Cluster with hash-tagged keys so rate-limit
> and lock ops stay single-shard; consider local L1 caches (Caffeine) in
> front of Redis for the exact cache with short TTLs, accepting slight
> staleness; batch metric increments; and re-examine single-flight polling
> (switch to pub/sub notification to cut waiter load). The stateless-replica
> property is what makes all of this incremental rather than a rewrite.

### Q16. What was the hardest bug or problem you hit?

> Two good ones. First, a distributed-correctness bug of my own making: my
> baseline "optimizations off" benchmark run silently still had caching on —
> the compose file wasn't passing the disable flags into the containers. I
> caught it because I *verified the precondition* (sent a repeat request and
> checked it was a miss — it wasn't) instead of trusting the config. Lesson:
> validate the experiment setup, not just the result. Second, a platform
> surprise: Spring Boot 4 had just moved to Jackson 3, where the package
> changed from `com.fasterxml.jackson.databind` to `tools.jackson.databind`
> and the starter no longer pulls it in — the app compiled locally against
> the wrong assumption and failed at container runtime with a missing
> ObjectMapper bean. Diagnosed from the startup log, fixed by adding the new
> starter and migrating imports. Lesson: on a brand-new major version, read
> the migration notes before trusting muscle memory.

### Q17. How did you validate the measured numbers? Could they be misleading?

> k6 against the full cluster through Nginx, 150 virtual users, with the
> traffic mix explicit because hit rate is entirely a function of traffic
> shape: the primary benchmark is a **realistic mostly-unique mix** (60%
> unique / 20% repeated / 20% reworded) → 141,885 requests, 47.1% hit rate,
> ~43% of provider spend avoided; a near-fully-repetitive stress run (1.34M
> requests, ~1,500 req/s, p99 513 ms, 99% hit rate) is reported strictly as
> the upper bound. My favorite part of this story: my first "unique" prompt
> generator was one template with a random number in it — unique as strings,
> but nearly identical to the embedding model, so they matched *each other*
> and inflated the semantic hit rate to 99%. I caught it because that number
> was implausible, rebuilt the generator around ~10⁹ topic/shape combinations,
> and re-ran. What else could mislead: the provider is a 200 ms mock (I'm
> measuring the gateway, not Groq — stated explicitly), and the
> optimizations-off baseline still benefits from coalescing (can't be
> disabled), which makes the savings figures conservative. All of this is
> written into RESULTS.md — I'd rather under-claim with caveats than
> over-claim.

### Q18. Where's the consistency boundary? What's eventually consistent here?

> Strongly consistent (single-key atomic ops in Redis): rate-limit counts,
> spend counters, the single-flight lock, key revocation — the correctness-
> critical state. Eventually/loosely consistent, by choice: circuit-breaker
> state (per instance), the live stats hash (increments race benignly),
> semantic-cache visibility (an entry appears after upsert; a request racing
> it just misses), and cache staleness up to TTL. The design rule: pay for
> consistency where a violation breaks a promise (limits, budgets, one-call
> coalescing); accept staleness where the cost of a violation is a slightly
> off dashboard or one redundant provider call.

### Q19. What would you change if this went to production?

> In rough priority order: (1) HA for the stateful pieces — Redis
> Sentinel/Cluster, Qdrant replication; (2) restore defensive layers I
> deliberately stripped — timeouts on every outbound call, retries with
> jitter, semantic cache degrading to miss when Qdrant is down instead of
> erroring; (3) security hardening — hashed key storage, TLS everywhere,
> secrets from a vault, real admin auth; (4) sliding-window rate limiting
> and Lua check-and-reserve budgets; (5) per-tenant cache namespacing;
> (6) CI/CD with staged rollout, and integration tests around the
> distributed properties (Testcontainers). I'd also add alerting rules on
> the Prometheus metrics — the dashboard exists, the paging doesn't.

### Q20. Why should a company let all its LLM traffic depend on your gateway?
(The "you built a SPOF" question.)

> That risk is exactly why the gateway is distributed: N stateless replicas
> behind a load balancer, no instance special, rolling deploys, and
> backpressure so overload degrades gracefully (fast, clean 503s for the
> excess — 1.5% under my stress test — rather than collapse). The remaining
> dependency is Redis, which gets HA in production (Sentinel/Cluster). And
> the failure math favors centralizing: without the gateway, every app
> independently risks provider outages, leaked keys, and budget blowouts —
> the gateway concentrates those risks into one place where they can
> actually be engineered against: failover, circuit breakers, caps, and a
> cache that keeps serving hits even while providers are down. You're not
> adding a point of failure so much as consolidating many unmanaged ones
> into one managed one.

---

## 8. Numbers to have memorized

| | |
|---|---|
| Primary benchmark (realistic mix) | 141,885 requests / 7 min / 150 VUs / 3 replicas |
| Hit rate (realistic mix) | 47.1% (39.6% exact + 7.6% semantic) |
| Spend / tokens avoided | ~43% / ~41% (per-request vs baseline: 21.6%) |
| Warm cache | 16 ms median |
| Stress run (repetitive traffic — quote with qualifier) | 1.34M requests · ~1,500 req/s · p99 513 ms · 99% hit rate |
| Coalescing | 20 concurrent identical → 1 provider call |
| Rate limit accuracy | 10-rpm key → exactly 10/30 across cluster |
| Threshold | 0.66 → 62% paraphrase recall, 0% false hits |
| Compression | 66% token cut on a verbose redundant prompt |
| Codebase | ~29 classes, ~1,750 lines of Java |
