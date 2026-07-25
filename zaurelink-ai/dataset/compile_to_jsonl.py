"""Compile step: JSON array → JSONL for training.
Reads the recombined JSON array and writes one record per line as JSONL —
the format Hugging Face's datasets library expects.
Prints a breakdown by (environment, app_user_language) so you can spot
coverage imbalances before training.
"""
import json
import os
import sys
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SOURCE_PATH = os.path.join(SCRIPT_DIR, "zaurelink_training_data_recombined.json")
OUTPUT_PATH = os.path.join(SCRIPT_DIR, "zaurelink_training_data.jsonl")
def main():
    if not os.path.exists(SOURCE_PATH):
        print(f"ERROR: Source file not found: {SOURCE_PATH}")
        print("Run recombine_app_user_language.py first to produce the recombined dataset.")
        sys.exit(1)
    with open(SOURCE_PATH, "r", encoding="utf-8") as f:
        records = json.load(f)
    counts = {}
    with open(OUTPUT_PATH, "w", encoding="utf-8") as out:
        for record in records:
            out.write(json.dumps(record, ensure_ascii=False))
            out.write("\n")
            key = (record.get("environment", "?"), record.get("app_user_language", "?"))
            counts[key] = counts.get(key, 0) + 1
    print(f"Compiled {len(records)} records to {OUTPUT_PATH}")
    print("\nBreakdown by environment / app_user_language:")
    for (env, lang), count in sorted(counts.items()):
        print(f"  {env} / {lang}: {count}")
    if counts:
        values = list(counts.values())
        min_count = min(values)
        max_count = max(values)
        if max_count > 0 and min_count / max_count < 0.5:
            print(
                f"\n  WARNING: The smallest group ({min_count}) is less than "
                f"half the largest ({max_count}). Consider collecting more data "
                f"for the underrepresented configuration before training."
            )
if __name__ == "__main__":
    main()
