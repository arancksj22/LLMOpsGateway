#!/usr/bin/env python3
"""Verify the distributed behaviors of the gateway cluster through the LB.

Run with the docker-compose cluster up:

    python scripts/distributed_check.py [--base http://localhost:8080]

Checks:
  1. Shared cache      — a response cached via one instance is served as an
                         exact hit by whichever instance handles the retry.
  2. Single-flight     — N concurrent identical cache-miss requests result in
                         ~1 provider call (the rest coalesce or hit cache).
  3. Rate limiting     — a key limited to R rpm gets ~R successes in
                         aggregate across all instances, rest are 429.
"""
import argparse
import json
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

ADMIN_KEY = "admin-secret"


def post(base, path, body, headers):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", **headers})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def chat(base, prompt, api_key="gw_demo"):
    return post(base, "/v1/chat/completions",
                {"messages": [{"role": "user", "content": prompt}], "temperature": 0},
                {"X-API-Key": api_key})


def check_shared_cache(base):
    prompt = f"Shared-cache probe {uuid.uuid4()}: what is a mutex?"
    s1, r1 = chat(base, prompt)
    time.sleep(0.5)
    seen = set()
    hits = 0
    for _ in range(6):
        s2, r2 = chat(base, prompt)
        g = r2.get("gateway", {})
        seen.add(g.get("instance"))
        if g.get("cache") == "exact":
            hits += 1
    ok = s1 == 200 and hits == 6
    print(f"[{'PASS' if ok else 'FAIL'}] shared cache: first={r1.get('gateway', {}).get('cache')}, "
          f"then {hits}/6 exact hits served by instances {sorted(x for x in seen if x)}")
    return ok


def check_single_flight(base, n=20):
    prompt = f"Coalescing probe {uuid.uuid4()}: define idempotency"
    with ThreadPoolExecutor(max_workers=n) as ex:
        results = list(ex.map(lambda _: chat(base, prompt), range(n)))
    misses = coalesced = hits = 0
    for status, body in results:
        g = body.get("gateway", {})
        if g.get("coalesced"):
            coalesced += 1
        elif g.get("cache") in ("exact", "semantic"):
            hits += 1
        elif g.get("cache") == "miss":
            misses += 1
    # non-coalesced misses == actual provider calls
    ok = misses <= 3 and (coalesced + hits) >= n - 3
    print(f"[{'PASS' if ok else 'FAIL'}] single-flight: {n} concurrent identical requests -> "
          f"{misses} provider call(s), {coalesced} coalesced, {hits} cache hits")
    return ok


def check_rate_limit(base, rpm=10, burst=30):
    status, key = post(base, "/admin/keys",
                       {"name": "ratelimit-test", "team": "test", "rpm": rpm},
                       {"X-Admin-Key": ADMIN_KEY})
    if status != 200:
        print(f"[FAIL] rate limit: could not create test key ({status}: {key})")
        return False
    api_key = key["key"]
    with ThreadPoolExecutor(max_workers=10) as ex:
        results = list(ex.map(
            lambda i: chat(base, f"rate limit probe {uuid.uuid4()}", api_key)[0], range(burst)))
    ok_count = sum(1 for s in results if s == 200)
    rejected = sum(1 for s in results if s == 429)
    ok = abs(ok_count - rpm) <= 2 and rejected >= burst - rpm - 2
    print(f"[{'PASS' if ok else 'FAIL'}] rate limit: key limited to {rpm} rpm -> "
          f"{ok_count} accepted, {rejected} rejected (429) out of {burst} across the cluster")
    return ok


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    args = parser.parse_args()
    results = [
        check_shared_cache(args.base),
        check_single_flight(args.base),
        check_rate_limit(args.base),
    ]
    print(f"\n{sum(results)}/{len(results)} distributed checks passed")
    raise SystemExit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
