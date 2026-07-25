"""Audit all batch files: extract distinct system prompts, count records by environment, flag mismatches."""
import json
import glob
import os
import sys
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATASET_DIR = os.path.dirname(SCRIPT_DIR)
if DATASET_DIR not in sys.path:
    sys.path.insert(0, DATASET_DIR)
import canonical_prompts
batches_dir = os.path.join(DATASET_DIR, "raw_batches")
distinct_prompts = {}
batch_stats = []
mismatches = []
no_system_turn = []
for filepath in sorted(glob.glob(os.path.join(batches_dir, "batch_*.json"))):
    with open(filepath, "r", encoding="utf-8") as f:
        records = json.load(f)
    fname = os.path.basename(filepath)
    env_counts = {"market": 0, "campus": 0, "other": 0}
    prompt_variants_in_batch = set()
    for i, rec in enumerate(records):
        env = rec.get("environment", "MISSING")
        env_counts[env] = env_counts.get(env, 0) + 1
        convs = rec.get("conversations", [])
        if not convs or convs[0].get("role") != "system":
            no_system_turn.append(f"{fname} record {i} (id={rec.get('id', '?')})")
            continue
        sys_content = convs[0].get("content", "")
        if sys_content not in distinct_prompts:
            distinct_prompts[sys_content] = []
        distinct_prompts[sys_content].append(f"{fname}:{rec.get('id', '?')}")
        prompt_variants_in_batch.add(sys_content[:80])
        if env == "market" and "Market Mode" not in sys_content:
            mismatches.append(f"{fname} rec {rec.get('id')}: env=market but prompt says '{sys_content[:60]}...'")
        if env == "campus" and "Campus Mode" not in sys_content:
            mismatches.append(f"{fname} rec {rec.get('id')}: env=campus but prompt says '{sys_content[:60]}...'")
    batch_stats.append({
        "file": fname,
        "total": len(records),
        "market": env_counts.get("market", 0),
        "campus": env_counts.get("campus", 0),
        "prompt_variants": len(prompt_variants_in_batch),
    })
print("=" * 80)
print("BATCH FILE AUDIT REPORT")
print("=" * 80)
print("\n--- Per-Batch Statistics ---")
total_all = 0
total_market = 0
total_campus = 0
for s in batch_stats:
    print(f"  {s['file']}: {s['total']} records (market={s['market']}, campus={s['campus']}, prompt_variants={s['prompt_variants']})")
    total_all += s['total']
    total_market += s['market']
    total_campus += s['campus']
print(f"  TOTAL: {total_all} records (market={total_market}, campus={total_campus})")
print(f"\n--- Distinct System Prompts Found: {len(distinct_prompts)} ---")
for idx, (prompt_text, examples) in enumerate(distinct_prompts.items()):
    print(f"\n  PROMPT #{idx+1} (used in {len(examples)} records)")
    print(f"  First 200 chars: {prompt_text[:200]}...")
    print(f"  Length: {len(prompt_text)} chars")
    print(f"  Has newlines: {'\\n' in prompt_text}")
    print(f"  ---")
if no_system_turn:
    print(f"\n--- Records WITHOUT a system turn: {len(no_system_turn)} ---")
    for x in no_system_turn[:20]:
        print(f"  {x}")
if mismatches:
    print(f"\n--- Environment / Prompt MISMATCHES: {len(mismatches)} ---")
    for m in mismatches[:20]:
        print(f"  {m}")
else:
    print("\n--- No environment/prompt mismatches found ---")
print("\n--- Canonical Prompt Comparison ---")
for prompt_text in distinct_prompts:
    if prompt_text == canonical_prompts.MARKET_EN:
        print("  Found EXACT match for canonical Market EN prompt")
    elif prompt_text == canonical_prompts.CAMPUS_EN:
        print("  Found EXACT match for canonical Campus EN prompt")
    elif prompt_text == canonical_prompts.MARKET_HA:
        print("  Found EXACT match for canonical Market HA prompt")
    elif prompt_text == canonical_prompts.CAMPUS_HA:
        print("  Found EXACT match for canonical Campus HA prompt")
    else:
        print(f"  Found NON-CANONICAL prompt (len={len(prompt_text)}): {prompt_text[:100]}...")
