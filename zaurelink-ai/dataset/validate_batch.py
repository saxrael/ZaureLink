import argparse
import json
import pathlib
import re
import sys
from collections import Counter
try:
    import canonical_prompts
    HAS_CANONICAL_PROMPTS = True
except ImportError:
    HAS_CANONICAL_PROMPTS = False
RECORD_ID_PATTERN = re.compile(r'^(MKT|CMP)-(HA|EN)-[0-9]{4}$')
ALLOWED_ENVIRONMENTS = {'market', 'campus'}
ALLOWED_LANGUAGES = {'ha', 'en'}
ALLOWED_SOURCES = {'fieldwork_seed', 'synthetic_permutation'}
ALLOWED_VERIFIERS = {'gemini_backtranslation', 'human_review'}
ALLOWED_RECORD_VERIFIED = {True, False, 'review'}
ALLOWED_ROLES = {'system', 'user', 'model'}
ALLOWED_RECORD_KEYS = {
    'id',
    'environment',
    'app_user_language',
    'conversations',
    'domain_tags',
    'source',
    'seed_scenario_id',
    'verified',
    'verifier',
    'review_note',
}
ALLOWED_TURN_KEYS = {'role', 'content', 'spoken_language'}
class ValidationResult:
    def __init__(self, path):
        self.path = pathlib.Path(path)
        self.errors = []
        self.warnings = []
        self.stats = {
            'records': 0,
            'turns': 0,
            'turn_counts': Counter(),
            'role_counts': Counter(),
            'source_counts': Counter(),
            'verifier_counts': Counter(),
            'environment_counts': Counter(),
            'app_user_language_counts': Counter(),
            'verified_counts': Counter(),
            'fieldwork_seed_issues': 0,
            'missing_user_spoken_language': 0,
            'missing_required_record_field': 0,
            'invalid_id': 0,
            'invalid_role': 0,
            'invalid_conversation_length': 0,
            'invalid_verified_value': 0,
            'invalid_spoken_language': 0,
            'unexpected_record_fields': 0,
            'unexpected_turn_fields': 0,
            'wrong_system_prompt': 0,
        }
    def add_error(self, record_id, message):
        self.errors.append((record_id, message))
    def add_warning(self, record_id, message):
        self.warnings.append((record_id, message))
    def report(self):
        print(f"VALIDATION for {self.path}")
        print(f"  records: {self.stats['records']}")
        print(f"  total turns: {self.stats['turns']}")
        print(f"  errors: {len(self.errors)}")
        print(f"  warnings: {len(self.warnings)}")
        print(f"  record sources: {dict(self.stats['source_counts'])}")
        print(f"  record verifiers: {dict(self.stats['verifier_counts'])}")
        print(f"  environments: {dict(self.stats['environment_counts'])}")
        print(f"  app_user_languages: {dict(self.stats['app_user_language_counts'])}")
        print(f"  verified values: {dict(self.stats['verified_counts'])}")
        print(f"  turn_counts: {dict(sorted(self.stats['turn_counts'].items()))}")
        print(f"  role_counts: {dict(self.stats['role_counts'])}")
        if self.errors:
            print('\nFirst 20 errors:')
            for record_id, message in self.errors[:20]:
                print(f"  - {record_id}: {message}")
        if self.warnings:
            print('\nFirst 20 warnings:')
            for record_id, message in self.warnings[:20]:
                print(f"  - {record_id}: {message}")
        print()
def validate_record(record, result):
    record_id = record.get('id', '<missing-id>')
    result.stats['records'] += 1
    for field in ['id', 'environment', 'app_user_language', 'conversations', 'source', 'verified']:
        if field not in record:
            result.stats['missing_required_record_field'] += 1
            result.add_error(record_id, f"missing required field '{field}'")
    extra_record_keys = set(record.keys()) - ALLOWED_RECORD_KEYS
    if extra_record_keys:
        result.stats['unexpected_record_fields'] += 1
        result.add_warning(record_id, f"unexpected record fields: {sorted(extra_record_keys)}")
    identifier = record.get('id')
    if not isinstance(identifier, str) or not RECORD_ID_PATTERN.match(identifier or ''):
        result.stats['invalid_id'] += 1
        result.add_error(record_id, f"invalid id '{identifier}'")
    environment = record.get('environment')
    if environment not in ALLOWED_ENVIRONMENTS:
        result.add_error(record_id, f"invalid environment '{environment}'")
    else:
        result.stats['environment_counts'][environment] += 1
    app_user_language = record.get('app_user_language')
    if app_user_language not in ALLOWED_LANGUAGES:
        result.add_error(record_id, f"invalid app_user_language '{app_user_language}'")
    else:
        result.stats['app_user_language_counts'][app_user_language] += 1
    source = record.get('source')
    if source not in ALLOWED_SOURCES:
        result.add_error(record_id, f"invalid source '{source}'")
    else:
        result.stats['source_counts'][source] += 1
    verified = record.get('verified')
    result.stats['verified_counts'][verified] += 1
    if verified not in ALLOWED_RECORD_VERIFIED:
        result.stats['invalid_verified_value'] += 1
        result.add_error(record_id, f"invalid verified value '{verified}'")
    elif verified == 'review':
        result.add_warning(record_id, "record-level verified='review' (allowed but non-boolean)")
    verifier = record.get('verifier')
    if verifier not in ALLOWED_VERIFIERS:
        result.add_error(record_id, f"invalid verifier '{verifier}'")
    else:
        result.stats['verifier_counts'][verifier] += 1
    domain_tags = record.get('domain_tags')
    if domain_tags is not None and not isinstance(domain_tags, list):
        result.add_error(record_id, "domain_tags must be an array of strings")
    elif isinstance(domain_tags, list):
        if not all(isinstance(tag, str) for tag in domain_tags):
            result.add_error(record_id, "domain_tags must only contain strings")
    seed_scenario_id = record.get('seed_scenario_id')
    if seed_scenario_id is not None and not isinstance(seed_scenario_id, str):
        result.add_error(record_id, "seed_scenario_id must be a string or null")
    review_note = record.get('review_note')
    if review_note is not None and not isinstance(review_note, str):
        result.add_error(record_id, "review_note must be a string or null")
    conv = record.get('conversations')
    if not isinstance(conv, list):
        result.add_error(record_id, "conversations must be an array")
        return
    if not (2 <= len(conv) <= 17):
        result.stats['invalid_conversation_length'] += 1
        result.add_error(record_id, f"conversation length {len(conv)} out of allowed range 2..17")
    if conv and conv[0].get('role') != 'system':
        result.add_warning(record_id, "first conversation item is not 'system'")
    if HAS_CANONICAL_PROMPTS and conv and conv[0].get('role') == 'system':
        env = record.get('environment')
        lang = record.get('app_user_language')
        if env in ALLOWED_ENVIRONMENTS and lang in ALLOWED_LANGUAGES:
            try:
                expected_prompt = canonical_prompts.get_prompt(env, lang)
                actual_prompt = conv[0].get('content', '')
                if actual_prompt != expected_prompt:
                    result.stats['wrong_system_prompt'] += 1
                    for ci, (a, b) in enumerate(zip(actual_prompt, expected_prompt)):
                        if a != b:
                            result.add_error(
                                record_id,
                                f"system prompt does not match canonical "
                                f"({env}/{lang}) — first diff at char {ci}: "
                                f"got {actual_prompt[max(0,ci-10):ci+20]!r}..."
                            )
                            break
                    else:
                        result.add_error(
                            record_id,
                            f"system prompt length mismatch vs canonical "
                            f"({env}/{lang}): got {len(actual_prompt)}, "
                            f"expected {len(expected_prompt)}"
                        )
            except KeyError:
                pass                                         
    for idx, turn in enumerate(conv):
        result.stats['turns'] += 1
        result.stats['turn_counts'][len(conv)] += 1
        if not isinstance(turn, dict):
            result.add_error(record_id, f"turn {idx} is not an object")
            continue
        extra_turn_keys = set(turn.keys()) - ALLOWED_TURN_KEYS
        if extra_turn_keys:
            result.stats['unexpected_turn_fields'] += 1
            result.add_warning(record_id, f"turn {idx} unexpected fields: {sorted(extra_turn_keys)}")
        role = turn.get('role')
        if role not in ALLOWED_ROLES:
            result.stats['invalid_role'] += 1
            result.add_error(record_id, f"turn {idx} invalid role '{role}'")
        else:
            result.stats['role_counts'][role] += 1
        content = turn.get('content')
        if not isinstance(content, str) or not content.strip():
            result.add_error(record_id, f"turn {idx} has invalid content")
        spoken_language = turn.get('spoken_language')
        if role == 'user':
            if spoken_language not in ALLOWED_LANGUAGES:
                result.stats['missing_user_spoken_language'] += 1
                result.add_error(record_id, f"turn {idx} user spoken_language missing or invalid: {spoken_language}")
        elif role == 'model':
            if spoken_language is not None and spoken_language not in ALLOWED_LANGUAGES:
                result.add_error(record_id, f"turn {idx} model spoken_language invalid: {spoken_language}")
        elif role == 'system':
            if spoken_language is not None:
                result.add_warning(record_id, f"turn {idx} system should not have spoken_language")
    if record.get('source') == 'fieldwork_seed':
        for idx, turn in enumerate(conv):
            if turn.get('role') == 'user' and turn.get('spoken_language') == 'ha':
                next_idx = idx + 1
                if next_idx < len(conv):
                    next_turn = conv[next_idx]
                    if next_turn.get('role') == 'model':
                        if next_turn.get('content') != turn.get('content'):
                            result.stats['fieldwork_seed_issues'] += 1
                            result.add_warning(record_id, f"fieldwork_seed user ha turn {idx} differs from next model content")
def validate_file(path):
    result = ValidationResult(path)
    p = pathlib.Path(path)
    if not p.exists():
        result.add_error('<file>', 'file not found')
        return result
    if not p.is_file():
        result.add_error('<file>', 'path is not a file')
        return result
    try:
        text = p.read_text(encoding='utf-8')
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        result.add_error('<file>', f'JSON parse error: {exc}')
        return result
    except Exception as exc:
        result.add_error('<file>', f'failed to read file: {exc}')
        return result
    if not isinstance(data, list):
        result.add_error('<file>', 'top-level JSON must be an array of records')
        return result
    for record in data:
        validate_record(record, result)
    return result
def main():
    parser = argparse.ArgumentParser(description='Validate ZaureLink batch JSON files.')
    parser.add_argument('paths', nargs='+', help='Batch JSON file paths to validate')
    parser.add_argument('--show-all', action='store_true', help='Show all issues, not just the first 20 of each type')
    args = parser.parse_args()
    expanded_paths = []
    for path in args.paths:
        if any(ch in path for ch in ['*', '?', '[']):
            expanded = list(pathlib.Path('.').glob(path))
            if expanded:
                expanded_paths.extend(str(p) for p in expanded)
            else:
                expanded_paths.append(path)
        else:
            expanded_paths.append(path)
    overall_errors = 0
    overall_warnings = 0
    overall_records = 0
    overall_turns = 0
    for path in expanded_paths:
        result = validate_file(path)
        result.report()
        overall_errors += len(result.errors)
        overall_warnings += len(result.warnings)
        overall_records += result.stats['records']
        overall_turns += result.stats['turns']
    print(f'OVERALL RECORDS: {overall_records}')
    print(f'OVERALL TURNS: {overall_turns}')
    if overall_errors:
        print(f'VALIDATION FAILED: {overall_errors} errors found across all files.')
        sys.exit(1)
    print(f'VALIDATION PASSED: {overall_warnings} warnings found across all files.')
    sys.exit(0)
if __name__ == '__main__':
    main()
