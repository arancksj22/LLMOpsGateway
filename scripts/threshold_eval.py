#!/usr/bin/env python3
"""Sweep the semantic-cache similarity threshold over labeled query pairs.

Uses the gateway's /admin/similarity endpoint (so it evaluates the exact
embedding the cache uses) and reports hit rate on equivalent pairs vs
false-hit rate on non-equivalent pairs per threshold.

    python scripts/threshold_eval.py [--base http://localhost:8080]
"""
import argparse
import json
import os
import urllib.parse
import urllib.request


def similarity(base, a, b):
    q = urllib.parse.urlencode({"a": a, "b": b})
    with urllib.request.urlopen(f"{base}/admin/similarity?{q}", timeout=30) as r:
        return json.load(r)["similarity"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8080")
    args = parser.parse_args()

    pairs_path = os.path.join(os.path.dirname(__file__), "pairs.json")
    with open(pairs_path, encoding="utf-8") as f:
        pairs = json.load(f)

    eq = [similarity(args.base, a, b) for a, b in pairs["equivalent"]]
    ne = [similarity(args.base, a, b) for a, b in pairs["non_equivalent"]]

    print(f"\nEquivalent pairs (n={len(eq)}):     min={min(eq):.3f} avg={sum(eq)/len(eq):.3f} max={max(eq):.3f}")
    print(f"Non-equivalent pairs (n={len(ne)}): min={min(ne):.3f} avg={sum(ne)/len(ne):.3f} max={max(ne):.3f}\n")

    print(f"{'threshold':>9} | {'hit rate':>8} | {'false-hit rate':>14}")
    print("-" * 40)
    best = None
    for t10 in range(60, 100, 2):
        t = t10 / 100
        hit = sum(1 for s in eq if s >= t) / len(eq)
        false_hit = sum(1 for s in ne if s >= t) / len(ne)
        marker = ""
        score = hit - 2 * false_hit  # penalize false hits harder
        if best is None or score > best[0]:
            best = (score, t)
        print(f"{t:>9.2f} | {hit:>7.0%} | {false_hit:>13.0%}")
    print(f"\nSuggested threshold (max hit-rate with false hits penalized 2x): {best[1]:.2f}")
    print("Set it via SEMANTIC_THRESHOLD in .env / application.yml.")


if __name__ == "__main__":
    main()
