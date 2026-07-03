# Distributed LLM Gateway — Build Brief (One-Shot Spec)

> **To the model building this:** Your job is to build this entire project in one pass as a complete, **runnable** repository in **Java + Spring Boot**. This document gives you the goals, context, rationale, scope, constraints, and tech stack — not the implementation details. Make the design decisions yourself.
>
> **Effort level — read this carefully:** This is a résumé/portfolio project, **not** production software. **Bare-minimum working code is the target.** No one will read the repository in depth. Prioritize **breadth over depth**: it is far more important that every feature listed exists and *runs* end-to-end than that any of it is robust, optimal, or elegant. Do **not** spend effort on exhaustive edge-case handling, defensive programming, clever optimizations, extensive abstractions, or comprehensive tests. Happy-path implementations are fine. The only hard requirements are: (1) it compiles and runs, (2) the local multi-instance cluster comes up, and (3) it works well enough to **produce the measured numbers the résumé bullets need** and to be demoed through a small UI. Aim for the simplest thing that satisfies the bullets in section 11 — nothing more.

---

## 1. What to build

A **distributed LLM gateway**: a self-hostable Spring Boot backend service that applications route their LLM calls through instead of calling providers (Groq / Gemini / OpenAI / Anthropic) directly. It is a centralized, horizontally-scalable proxy that reduces cost, centralizes organizational control, and stays reliable under load. Ship it with a **small web UI** for demoing and viewing stats, a local multi-instance cluster, and a README.

---

## 2. Goal & context

Organizations increasingly have **many** applications and services all calling LLMs. Calling providers directly from each app creates real problems: duplicated spend, scattered API keys, no way to enforce company-wide budgets, no unified view of cost/usage, and no shared protection against the provider's org-wide rate limits. This gateway solves those by becoming the single controlled path through which all LLM traffic flows.

Three intertwined value propositions, all first-class:

1. **Cost efficiency** — avoid redundant LLM calls, and shrink the calls that are unavoidable.
2. **Organizational control (centralization)** — one place to manage keys, budgets, rate limits, policy, and observability across all apps.
3. **Reliability at scale** — horizontal scaling, provider failover, and graceful overload handling so the shared path never becomes a fragile bottleneck.

### 2.1 The two jobs the gateway does (conceptual model)
- **Job A — avoid the call:** if a meaning-equivalent request was already answered, return the stored answer without calling any provider.
- **Job B — shrink the call:** when a call is unavoidable, reduce the tokens it consumes.

Guiding sentence: *"For every request: first try to skip the call; if I must call, make it cheap; and no matter what, centrally control and observe it."*

---

## 3. Why centralized (the organizational-control rationale)

A single application could use a local library instead. The gateway earns its existence only because some capabilities **require seeing or controlling all traffic at once** — which a per-app library structurally cannot do. Build these as genuine (if minimal) features:

- **Org-wide spend caps & cost attribution.** Enforce budgets like "the company/team spends at most $X per month" and attribute cost per app/team. Impossible per-app, since no single app sees total spend. *This is the single strongest justification for the gateway.*
- **Centralized API-key management.** Real provider keys live server-side in one place; applications authenticate with their own scoped, revocable internal keys and never hold the master keys.
- **Global rate limiting against the provider's shared limit.** Providers rate-limit the whole organization; only a central point can see total traffic and throttle to stay under that ceiling.
- **Centralized observability & audit.** One place with every call's cost, latency, tokens, and metadata.
- **Single point of policy & provider control.** Switch providers or apply a policy once, and every app is covered.

**Scoping note:** caching by itself is *not* a strong reason for centralization (it largely works per-app). The defining purpose of the gateway is **organizational centralization/control**; efficiency features ride on top.

---

## 4. Why distributed (the scale & availability rationale)

Centralization gets you to *one shared service*. Distribution — running *multiple copies* behind a load balancer — is a separate requirement, driven by that service being shared critical infrastructure on the request path:

- **Load:** a single instance has a throughput ceiling; the whole org's traffic will exceed it.
- **Availability / single point of failure:** if the one instance dies, every app loses LLM access at once.
- **Zero-downtime deploys:** you can't take the whole org offline to ship an update; multiple instances let updates roll one at a time. (Holds even at modest scale.)

**Key engineering consequence to design around:** because multiple instances cannot share in-memory state, all shared state — cache, rate-limit counters, coordination locks, budgets, keys — must live in **central stores every instance reaches** (Redis + a vector DB), and gateway instances must be **stateless**. Ensure correct behavior *across instances* for: a shared cache (a response cached by one instance is served by any other), de-duplication of concurrent identical work (many identical requests hitting an empty cache across instances trigger only one provider call, shared to the rest), rate limits that hold in aggregate, and shared cost/budget counters consistent under concurrent updates.

**Honest scope note (put in README):** at small scale a single instance suffices; distribution is built to demonstrate the *capability* for scale/availability, proven via a local multi-instance cluster and load testing — not a claim of production traffic. Minimal correctness here is fine — it needs to *demonstrably work* across instances, not be bulletproof.

---

## 5. Feature scope

Build all **CORE** features so they exist and run. Keep each minimal. **STRETCH** features are optional and only if trivial to add.

### Cost efficiency
- **Exact-match request deduplication** *(CORE)* — return a stored response for a byte-identical prior request; cheapest path, checked before semantic matching.
- **Semantic caching** *(CORE — centerpiece)* — return a stored response for a **meaning-equivalent** prior query via embeddings + vector similarity. Support a tunable similarity threshold, entry expiry, and a simple cacheability policy (e.g. skip caching for high-temperature requests). This is the main hit-rate driver.
- **Prompt compression** *(CORE)* — reduce outgoing prompt tokens while roughly preserving meaning via a **simple token-importance / heuristic trimmer** (do NOT port LLMLingua or build anything sophisticated; a lightweight trimmer that measurably cuts token count is enough). Toggleable; log tokens before/after.
- **Conversation history pruning** *(STRETCH)* — keep recent turns, drop/summarize older ones.
- **Provider prompt-caching passthrough** *(STRETCH)* — enable a provider's own prefix caching where trivially supported.

### Distributed correctness
- **Single-flight request coalescing** *(CORE)* — collapse concurrent duplicate cache-miss work into one provider call, result shared, correct across instances.
- **Distributed rate limiting** *(CORE)* — per-key request limits enforced in aggregate across all instances.

### Organizational control
- **Internal API-key management** *(CORE)* — scoped, revocable per-app keys; provider keys server-side only; simple endpoints to create/revoke/inspect keys.
- **Spend caps & cost accounting** *(CORE)* — compute per-call cost from tokens × per-model pricing; track spend per key/team/org; enforce configurable budget caps (enforce-or-warn).
- **Observability** *(CORE)* — expose request rate, latency percentiles, cache hit rate (exact vs semantic), tokens, cost per team, rate-limit/queue rejections, provider errors, coalescing effect — as Prometheus metrics (via Spring Boot Actuator + Micrometer) with a provisioned Grafana dashboard.

### Reliability
- **Multi-provider fallback & circuit breaking** *(CORE)* — ordered failover across providers; stop calling a persistently failing provider for a cooldown, then probe.
- **SSE streaming passthrough** *(CORE)* — stream tokens to the client as they arrive while capturing the full response for logging/metrics.
- **Backpressure** *(CORE)* — bound concurrent in-flight work; reject cleanly when overloaded rather than collapsing.

### Interfaces
- **API:** an **OpenAI-compatible-style chat completions endpoint** so existing clients can point at the gateway, plus health/readiness endpoints and the metrics endpoint. Requests carry optional gateway controls (per-request cache/compress toggles). All thresholds, limits, budgets, and pricing must be **configuration**, never hardcoded.
- **Small web UI** *(CORE)* — a minimal single-page dashboard (kept as simple as possible) that lets a user: send a prompt through the gateway and see the response, whether it was a cache hit (exact/semantic/miss), latency, tokens, and estimated cost; and view a few live stats (total requests, hit rate, total cost/tokens saved). This is for **demoing** the gateway and making the value visible — keep it bare-bones (plain HTML/JS or a tiny React page, or Spring server-rendered templates — whatever is least effort). It does not need to be pretty or feature-rich.

---

## 6. Tech stack (Java / Spring Boot)

**Language/framework: Java + Spring Boot** (build with Maven or Gradle — pick one). Keep the module/package structure simple.

- **Web / API:** Spring Boot (Spring Web; use Spring WebFlux only if it makes SSE streaming and async provider calls easier — otherwise plain Spring MVC with SSE is fine).
- **Shared cache, counters, coordination locks, keys, budgets:** Redis via Spring Data Redis (Lettuce/Jedis). Free hosting: Upstash Redis or Redis Cloud free tier; local: Redis container. Use Redis for atomic counters/locks (a Lua script or Redisson is fine for the atomic bits — whatever is least effort).
- **Vector database (semantic similarity):** Qdrant (open-source, has a Java client, free cloud tier) or Chroma. Abstract behind a small interface so it's swappable. Keep it minimal.
- **Embeddings:** use **Spring AI's local ONNX transformer embedding** (runs `all-MiniLM-L6-v2`, 384-dim, on-CPU, free) if convenient; otherwise Deep Java Library (DJL) with the same model, or a free hosted embedding API. Abstract behind an interface.
- **LLM providers:** **Groq** (free tier; OpenAI-compatible API, so an OpenAI-style client pointed at Groq's base URL works) and **Google Gemini** (free tier) as the two defaults — two providers make failover real at zero cost. **Spring AI** is recommended to simplify provider + embedding integration (it has OpenAI/Groq-compatible and Gemini clients and embedding abstractions). Abstract each provider behind a common interface; OpenAI/Anthropic optional paid add-ons.
- **Prompt compression:** a simple in-house heuristic trimmer (see section 5) — no heavy library.
- **Load balancer:** Nginx.
- **Containerization / local cluster:** Docker + docker-compose (Nginx + multiple gateway replicas + Redis + vector DB + Prometheus + Grafana).
- **Observability:** Spring Boot Actuator + Micrometer exposing a Prometheus endpoint; Prometheus + Grafana with a provisioned dashboard.
- **Resilience helpers:** Resilience4j is fine for circuit breaking / rate limiting / bulkhead if it reduces effort (Spring Boot integrates it cleanly) — but the rate limiting that must hold *across instances* has to be Redis-backed, not purely in-process.
- **Load testing:** k6 (or Locust).
- **CI/CD:** GitHub Actions (build + minimal test). Keep it trivial.

---

## 7. Constraints & deployment context

- **Fully free.** Every component must run on free tiers or locally at zero cost (free Groq/Gemini tiers, free/local Redis + vector DB, local embedding model).
- **Deploy target: Render (free tier) for a public single-instance demo.** Render's free tier spins services down after ~15 min idle (cold start) and does not run multiple load-balanced instances for free. A Spring Boot app fits, but watch memory on the 512MB free tier — keep the local embedding model lightweight, and if RAM is tight, it's acceptable for the Render demo to use a hosted embedding API while the local cluster uses the local model.
- **Demonstrate the distributed system locally.** The full multi-instance cluster runs via docker-compose locally — this is where distributed behavior is validated and load-tested. A single instance goes to Render as the public demo (with the UI).
- **Statelessness is mandatory** for gateway instances — all shared state external.
- **Honesty in the README:** frame results as capability validated via a local cluster and load testing (k6), with a single-instance live demo; don't imply production-scale traffic or a scaled cloud deployment.

---

## 8. What to produce (deliverables)

1. The Spring Boot gateway service implementing all CORE features (minimal, happy-path implementations are fine).
2. The **small web UI** (section 5) for demo + stats.
3. A `docker-compose` local cluster: Nginx + multiple gateway replicas + Redis + vector DB + Prometheus + Grafana (dashboard provisioned).
4. A **k6 load-test script** that generates the traffic (mix of repeated, reworded, unique, and burst-of-identical queries) needed for the résumé numbers.
5. A **threshold-evaluation script/utility** that sweeps the semantic-cache similarity threshold over a small labeled set of equivalent vs. non-equivalent query pairs and reports hit-rate vs. false-hit-rate, so the chosen threshold is data-justified. (Keep it simple — a small script is fine.)
6. A **README** covering: what/why (goals, org-control rationale, distributed rationale), a simple architecture description, how to run the local cluster and the UI, how to run the load test + threshold eval, the honest deployment framing, and a results section with placeholders to fill in from measured runs.

Minimal tests only — enough to show the multi-instance behaviors work (shared cache across instances, single-flight yielding one provider call under concurrent duplicates, rate limit holding in aggregate). Do not aim for coverage.

---

## 9. Non-goals & effort ceiling

- No fine-tuning, model training, or self-hosted model *serving* (GPU-bound; out of scope).
- No polished/production UI — the small demo UI is intentionally bare-bones.
- **No gold-plating.** Do not handle unusual edge cases, add elaborate error taxonomies, build extensive abstractions, optimize hot paths, or write exhaustive tests. Happy-path, minimal, and working beats thorough. If a choice is between "more robust" and "less code," choose less code.
- Do not over-stack efficiency techniques; unmeasured features add no value.
- STRETCH features only if trivial and after all CORE features run.

---

## 10. Results to measure (for the README & résumé)

Make it easy to measure and record: token-cost reduction % (caching + compression, on vs. off), cache hit rate (exact vs. semantic), warm cached-response latency, throughput (req/s) and latency p50/p95/p99 under load, coalescing effect (provider calls saved under concurrent-duplicate bursts), and the threshold hit-rate/false-hit-rate table. Report request volume as "100K+ requests in load testing," not "users." These numbers exist to back the bullets below — the code only needs to be good enough to produce them.

---

## 11. Résumé bullets this project must support

(The whole point of the build. Put these in the README's "impact" section; numbers filled from measured runs. Each names a specific mechanism and carries a real, reproducible metric. The implementation only needs to be sufficient to make each of these true and demonstrable.)

- **Efficiency:** Built a distributed LLM gateway that cut token costs ~[X]% and served warm cached responses in ~[Y]ms by layering exact-match deduplication, semantic caching (embeddings + vector database), and token-importance prompt compression, measured across 100K+ benchmarked requests.
- **Distributed systems:** Scaled the gateway horizontally behind a load balancer with shared cache and vector state across stateless instances, using single-flight request coalescing to collapse duplicate concurrent cache misses and atomic Redis-backed rate limiting to enforce per-key quotas across all nodes.
- **Centralized control:** Built a central control plane for organizational LLM access — server-side provider-key storage with scoped, revocable per-application keys, org-wide spend-cap enforcement with per-team cost attribution, and full usage/cost/latency observability via Prometheus and Grafana.
- **Reliability:** Engineered fault tolerance with multi-provider fallback and circuit breaking, SSE streaming passthrough, and backpressure via a bounded request queue, validated under k6 load testing at [X] req/s and [Y]ms p99.
