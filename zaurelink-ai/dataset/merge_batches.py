import json
import glob
all_records = []
for filepath in sorted(glob.glob("dataset/raw_batches/batch_*.json")):
    with open(filepath, "r", encoding="utf-8") as f:
        batch = json.load(f)
        all_records.extend(batch)
        print(f"{filepath}: {len(batch)} records")
print(f"Total records: {len(all_records)}")
with open("dataset/zaurelink_training_data.json", "w", encoding="utf-8") as f:
    json.dump(all_records, f, ensure_ascii=False, indent=2)
print("Written to dataset/zaurelink_training_data.json")
