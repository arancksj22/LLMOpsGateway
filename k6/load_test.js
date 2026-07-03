// k6 load test for the LLM gateway cluster.
//
//   k6 run k6/load_test.js                         # default: ~5 min, 100 VUs
//   k6 run -e BASE=http://localhost:8080 -e VUS=150 -e DURATION=10m k6/load_test.js
//
// Traffic mix (drives the resume numbers):
//   40% repeated identical prompts  -> exact-cache hits
//   30% reworded variants           -> semantic-cache hits
//   30% unique prompts              -> misses (provider calls)
// plus a burst scenario of concurrent identical fresh prompts -> coalescing.
//
// Run against the mock provider (PROVIDERS_ORDER=mock or no API keys) so
// 100K+ requests cost $0 while exercising every gateway code path.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'gw_demo';
const VUS = parseInt(__ENV.VUS || '100');
const DURATION = __ENV.DURATION || '5m';

const cacheExact = new Counter('gateway_cache_exact');
const cacheSemantic = new Counter('gateway_cache_semantic');
const cacheMiss = new Counter('gateway_cache_miss');
const coalesced = new Counter('gateway_coalesced');
const hitLatency = new Trend('gateway_cached_latency', true);

const REPEATED = [
  'What is a distributed cache?',
  'Explain the CAP theorem briefly.',
  'What does a load balancer do?',
  'Define horizontal scaling in one sentence.',
  'What is an API rate limit?',
  'Explain what Redis is used for.',
  'What is a vector database?',
  'How does DNS resolution work, briefly?',
  'What is eventual consistency?',
  'Explain circuit breakers in microservices.',
];

// Base prompt -> reworded variants (should land as semantic hits once the base is cached)
const REWORDED = [
  ['What is a distributed cache?', ['Tell me what a distributed cache is', 'Can you describe a distributed cache?', 'distributed cache - what is it?']],
  ['Explain the CAP theorem briefly.', ['Give me a brief explanation of the CAP theorem', 'Briefly explain CAP theorem']],
  ['What does a load balancer do?', ['Describe what a load balancer does', 'What is the job of a load balancer?']],
  ['What is an API rate limit?', ['Explain API rate limits', 'What are rate limits for an API?']],
  ['What is a vector database?', ['Tell me about vector databases', 'Describe what a vector database is']],
];

export const options = {
  scenarios: {
    steady_mix: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      exec: 'mix',
    },
    coalescing_bursts: {
      executor: 'per-vu-iterations',
      vus: 30,
      iterations: 4,
      startTime: '30s',
      exec: 'burst',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

function send(prompt, tag) {
  const res = http.post(`${BASE}/v1/chat/completions`, JSON.stringify({
    model: 'gateway-default',
    messages: [{ role: 'user', content: prompt }],
    temperature: 0,
  }), {
    headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEY },
    tags: { kind: tag },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
  if (res.status === 200) {
    try {
      const g = res.json().gateway;
      if (g.cache === 'exact') { cacheExact.add(1); hitLatency.add(res.timings.duration); }
      else if (g.cache === 'semantic') { cacheSemantic.add(1); hitLatency.add(res.timings.duration); }
      else cacheMiss.add(1);
      if (g.coalesced) coalesced.add(1);
    } catch (e) { /* ignore parse issues */ }
  }
  return res;
}

export function mix() {
  const r = Math.random();
  if (r < 0.4) {
    send(REPEATED[Math.floor(Math.random() * REPEATED.length)], 'repeated');
  } else if (r < 0.7) {
    const [base, variants] = REWORDED[Math.floor(Math.random() * REWORDED.length)];
    const prompt = Math.random() < 0.3 ? base : variants[Math.floor(Math.random() * variants.length)];
    send(prompt, 'reworded');
  } else {
    send(`Unique question ${__VU}-${__ITER}-${Date.now()}: summarize the number ${Math.random()}`, 'unique');
  }
}

// 30 VUs fire the SAME fresh prompt concurrently -> single-flight should
// collapse each burst into ~1 provider call.
export function burst() {
  send(`Coalescing burst probe iteration ${__ITER}: what is idempotency?`, 'burst');
}
