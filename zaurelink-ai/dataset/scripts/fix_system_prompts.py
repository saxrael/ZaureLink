"""Fix system prompts in all batch files.
For every record in every batch_*.json file under raw_batches/:
  1. Selects the correct canonical prompt based on (environment, app_user_language).
  2. Replaces conversations[0].content with the canonical text.
  3. Backs up the original file to raw_batches/backup_originals/ before overwriting.
No other field in any record is touched.
"""
import json
import glob
import os
import shutil
import sys
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATASET_DIR = os.path.dirname(SCRIPT_DIR)
if DATASET_DIR not in sys.path:
    sys.path.insert(0, DATASET_DIR)
import canonical_prompts
BATCHES_DIR = os.path.join(DATASET_DIR, "raw_batches")
BACKUP_DIR = os.path.join(BATCHES_DIR, "backup_originals")
def fix_batch(filepath: str) -> dict:
    """Fix all system prompts in a single batch file. Returns stats."""
    with open(filepath, "r", encoding="utf-8") as f:
        records = json.load(f)
    stats = {
        "total": len(records),
        "changed": 0,
        "unchanged": 0,
        "errors": [],
    }
    for i, rec in enumerate(records):
        rec_id = rec.get("id", f"<index-{i}>")
        env = rec.get("environment")
        lang = rec.get("app_user_language")
        if env not in ("market", "campus"):
            stats["errors"].append(f"{rec_id}: invalid environment '{env}'")
            continue
        if lang not in ("en", "ha"):
            stats["errors"].append(f"{rec_id}: invalid app_user_language '{lang}'")
            continue
        convs = rec.get("conversations")
        if not convs or not isinstance(convs, list):
            stats["errors"].append(f"{rec_id}: missing or invalid conversations array")
            continue
        if convs[0].get("role") != "system":
            stats["errors"].append(f"{rec_id}: first turn is not role=system")
            continue
        correct_prompt = canonical_prompts.get_prompt(env, lang)
        old_prompt = convs[0]["content"]
        if old_prompt == correct_prompt:
            stats["unchanged"] += 1
        else:
            convs[0]["content"] = correct_prompt
            stats["changed"] += 1
    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False, indent=2)
    return stats
def main():
    batch_files = sorted(glob.glob(os.path.join(BATCHES_DIR, "batch_*.json")))
    if not batch_files:
        print(f"ERROR: No batch files found in {BATCHES_DIR}")
        return
    os.makedirs(BACKUP_DIR, exist_ok=True)
    total_changed = 0
    total_unchanged = 0
    total_errors = 0
    total_records = 0
    for filepath in batch_files:
        fname = os.path.basename(filepath)
        backup_path = os.path.join(BACKUP_DIR, fname)
        if not os.path.exists(backup_path):
            shutil.copy2(filepath, backup_path)
        stats = fix_batch(filepath)
        total_records += stats["total"]
        total_changed += stats["changed"]
        total_unchanged += stats["unchanged"]
        total_errors += len(stats["errors"])
        status = "OK" if not stats["errors"] else "ERRORS"
        print(
            f"  {fname}: {stats['total']} records -- "
            f"{stats['changed']} changed, {stats['unchanged']} unchanged, "
            f"{len(stats['errors'])} errors [{status}]"
        )
        for err in stats["errors"]:
            print(f"    ERROR: {err}")
    print(f"\n{'='*60}")
    print(f"TOTAL: {total_records} records across {len(batch_files)} files")
    print(f"  Changed:   {total_changed}")
    print(f"  Unchanged: {total_unchanged}")
    print(f"  Errors:    {total_errors}")
    if total_errors == 0:
        print("All system prompts fixed successfully.")
    else:
        print("WARNING: Some records had errors -- see above.")
if __name__ == "__main__":
    main()
