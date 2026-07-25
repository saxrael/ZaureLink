"""Extract all fallback English lines and write them into hausa_equivalents.json as empty-string values."""
import json
import os
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RECOMBINED = os.path.join(SCRIPT_DIR, "zaurelink_training_data_recombined.json")
EQUIV_FILE = os.path.join(SCRIPT_DIR, "hausa_equivalents.json")
with open(RECOMBINED, "r", encoding="utf-8") as f:
    data = json.load(f)
try:
    with open(EQUIV_FILE, "r", encoding="utf-8") as f:
        existing = json.load(f)
except FileNotFoundError:
    existing = {}
fallbacks = set()
for r in data:
    if r.get("app_user_language") != "ha":
        continue
    convs = r.get("conversations", [])
    for i, t in enumerate(convs):
        if (
            t.get("role") == "user"
            and t.get("spoken_language") == "en"
            and i + 1 < len(convs)
            and convs[i + 1].get("role") == "model"
            and convs[i + 1]["content"] == t["content"]
        ):
            fallbacks.add(t["content"])
merged = dict(existing)
new_count = 0
for text in sorted(fallbacks):
    if text not in merged:
        merged[text] = ""
        new_count += 1
sorted_merged = dict(sorted(merged.items()))
with open(EQUIV_FILE, "w", encoding="utf-8") as f:
    json.dump(sorted_merged, f, ensure_ascii=False, indent=2)
print(f"Existing entries preserved: {len(existing)}")
print(f"New entries added (empty): {new_count}")
print(f"Total entries in {EQUIV_FILE}: {len(sorted_merged)}")
