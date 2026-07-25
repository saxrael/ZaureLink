"""Deterministic recombination: generate mirrored app_user_language records.
Takes the merged dataset (all records are app_user_language="en") and produces
a mirrored "ha" version of each record by reassigning roles and prompts
deterministically — no LLM involved, no new content invented.
Two rules:
  Rule 1 (free pass-through): Other-party spoke Hausa, was translated to
    English. Under mirrored config, app user now wants Hausa too — same Hausa
    speech is relayed unchanged.
  Rule 2 (lookup or fallback): App-user spoke English, was translated to Hausa.
    Under mirrored config, the app user's natural speech would be Hausa — use
    the verified Hausa equivalent from the lookup file. If not found, fall back
    to a pass-through (app user code-switches into English).
Outputs the COMBINED dataset (original en + mirrored ha records).
"""
import json
import os
import sys
import canonical_prompts
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SOURCE_PATH = os.path.join(SCRIPT_DIR, "zaurelink_training_data.json")
LOOKUP_PATH = os.path.join(SCRIPT_DIR, "hausa_equivalents.json")
OUTPUT_PATH = os.path.join(SCRIPT_DIR, "zaurelink_training_data_recombined.json")
def mirrored_prompt(environment: str) -> str:
    """Get the canonical 'ha' system prompt for the given environment."""
    return canonical_prompts.get_prompt(environment, "ha")
def recombine_record(record: dict, lookup: dict, seq: int, stats: dict) -> dict | None:
    """Create the mirrored (ha) version of an en-config record."""
    if record.get("app_user_language") != "en":
        return None                                                   
    env = record.get("environment")
    if env not in ("market", "campus"):
        stats["errors"].append(f"Record {record.get('id')}: invalid environment '{env}'")
        return None
    new_conversations = []
    for turn in record["conversations"]:
        if turn["role"] == "system":
            new_conversations.append({
                "role": "system",
                "content": mirrored_prompt(env),
            })
            continue
        if turn["role"] == "user":
            new_conversations.append(dict(turn))
            continue
        prior_user = new_conversations[-1]
        spoken = prior_user.get("spoken_language")
        if spoken == "ha":
            new_conversations[-1] = {
                "role": "user",
                "content": prior_user["content"],
                "spoken_language": "ha",
            }
            new_conversations.append({"role": "model", "content": prior_user["content"]})
            stats["rule_1_free"] += 1
        elif spoken == "en":
            english_text = prior_user["content"]
            hausa_equivalent = lookup.get(english_text)
            if hausa_equivalent:
                new_conversations[-1] = {
                    "role": "user",
                    "content": hausa_equivalent,
                    "spoken_language": "ha",
                }
                new_conversations.append({"role": "model", "content": english_text})
                stats["rule_2_full"] += 1
            else:
                new_conversations[-1] = {
                    "role": "user",
                    "content": english_text,
                    "spoken_language": "en",
                }
                new_conversations.append({"role": "model", "content": english_text})
                stats["rule_2_fallback"] += 1
                stats["fallback_texts"].add(english_text)
        else:
            stats["errors"].append(
                f"Record {record.get('id')}: user turn missing spoken_language: "
                f"{prior_user}"
            )
            new_conversations.append(dict(turn))
    env_prefix = "MKT" if record["environment"] == "market" else "CMP"
    mirrored = dict(record)
    mirrored["id"] = f"{env_prefix}-HA-{seq:04d}"
    mirrored["app_user_language"] = "ha"
    mirrored["conversations"] = new_conversations
    mirrored["seed_scenario_id"] = record.get("seed_scenario_id") or record["id"]
    mirrored["verified"] = record["verified"]
    mirrored["verifier"] = record.get("verifier", "human_review")
    return mirrored
def main():
    if not os.path.exists(SOURCE_PATH):
        print(f"ERROR: Source file not found: {SOURCE_PATH}")
        print("Run merge_batches.py first to produce zaurelink_training_data.json")
        sys.exit(1)
    with open(SOURCE_PATH, "r", encoding="utf-8") as f:
        records = json.load(f)
    try:
        with open(LOOKUP_PATH, "r", encoding="utf-8") as f:
            lookup = json.load(f)
        print(f"Loaded {len(lookup)} Hausa equivalent entries from {LOOKUP_PATH}")
    except FileNotFoundError:
        lookup = {}
        print(f"WARNING: {LOOKUP_PATH} not found — every Rule 2 case will use the fallback.")
    stats = {
        "rule_1_free": 0,
        "rule_2_full": 0,
        "rule_2_fallback": 0,
        "fallback_texts": set(),
        "errors": [],
    }
    mirrored_records = []
    seq = 1
    for record in records:
        mirrored = recombine_record(record, lookup, seq, stats)
        if mirrored is not None:
            mirrored_records.append(mirrored)
            seq += 1
    combined = records + mirrored_records
    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(combined, f, ensure_ascii=False, indent=2)
    print(f"\nSource records (en-config): {len(records)}")
    print(f"Mirrored records generated (ha-config): {len(mirrored_records)}")
    print(f"  Rule 1 (free pass-through): {stats['rule_1_free']}")
    print(f"  Rule 2 (full, used lookup): {stats['rule_2_full']}")
    print(f"  Rule 2 (fallback, no lookup entry): {stats['rule_2_fallback']}")
    if stats["fallback_texts"]:
        print(
            "\n  English lines missing a Hausa equivalent "
            "(add these to hausa_equivalents.json to upgrade to full-strength):"
        )
        for text in sorted(stats["fallback_texts"]):
            print(f"    - {text}")
    if stats["errors"]:
        print(f"\n  ERRORS ({len(stats['errors'])}):")
        for err in stats["errors"]:
            print(f"    - {err}")
    print(f"\nTotal combined dataset: {len(combined)} records")
    print(f"Written to {OUTPUT_PATH}")
if __name__ == "__main__":
    main()
