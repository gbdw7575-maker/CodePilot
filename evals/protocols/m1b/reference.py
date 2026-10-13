"""Design-only reference oracle. Authoritative context/ledger are synthetic host data, not model inputs."""
import copy
import hashlib
import json
from pathlib import Path

HASH_FIELDS = ['taskDefinitionDigest', 'requirementsDigest', 'registryDigest', 'checksDigest',
               'upstreamInputDigest', 'workspaceBeforeDigest', 'workspaceAfterDigest',
               'imageDigest', 'policyDigest', 'verifierSourceDigest']
ID_FIELDS = ['projectId', 'rootRunId', 'turnId', 'planId', 'taskId', 'registrationId',
             'attemptId', 'childRunId']
REGISTERED = HASH_FIELDS[:5]


def pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError('duplicate JSON key')
        result[key] = value
    return result


def parse(raw):
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('non-finite number')))


def canonical(value):
    def check(v):
        if v is None or type(v) is bool:
            return
        if type(v) is int and abs(v) <= 9007199254740991:
            return
        if type(v) is str:
            v.encode('utf-8', errors='strict')
            return
        if type(v) is list:
            for child in v:
                check(child)
            return
        if type(v) is dict and all(type(k) is str and k.isascii() for k in v):
            for child in v.values():
                check(child)
            return
        raise ValueError('unsupported canonical value')
    check(value)
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False,
                      allow_nan=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def object_schema(properties):
    return dict(type='object', properties=properties, required=list(properties), additionalProperties=False)


def schema():
    string = dict(type='string', minLength=1, maxLength=128)
    sha = dict(type='string', pattern='^[0-9a-f]{64}$')
    integer = dict(type='integer', minimum=1, maximum=9007199254740991)
    ref = object_schema(dict(id=string, sha256=sha, sizeBytes=dict(type='integer', minimum=0, maximum=1048576)))
    binding = object_schema({**{k: string for k in ID_FIELDS}, **{k: sha for k in HASH_FIELDS},
                             'taskVersion': integer, 'fencingToken': integer})
    execution = object_schema(dict(exitCode=dict(type='integer', minimum=-65536, maximum=65536),
                                   timedOut=dict(type='boolean'), cancelled=dict(type='boolean'),
                                   cleanupConfirmed=dict(type='boolean')))
    return {'$schema': 'https://json-schema.org/draft/2020-12/schema',
            'title': 'M1B design-only evidence claim; NOT an active API schema',
            **object_schema(dict(version=dict(type='string', const='dsh-task-acceptance-v1-design'),
                                 mode=dict(type='string', const='EXECUTABLE_CHECKS'), binding=binding,
                                 childState=dict(type='string', const='COMPLETED'),
                                 summary=dict(type='string', maxLength=4000),
                                 checkRefs=dict(type='array', minItems=1, maxItems=8, uniqueItems=True, items=string),
                                 reportRef=ref, outputs=dict(type='array', maxItems=32, items=ref), execution=execution))}


def validate(value, rule):
    import re
    kind = rule['type']
    if kind == 'object':
        if type(value) is not dict or set(value) != set(rule['required']):
            raise ValueError('missing or unknown object field')
        for key, item in value.items():
            validate(item, rule['properties'][key])
    elif kind == 'array':
        if type(value) is not list or not rule.get('minItems', 0) <= len(value) <= rule.get('maxItems', 1000):
            raise ValueError('invalid array')
        if rule.get('uniqueItems') and len({canonical(v) for v in value}) != len(value):
            raise ValueError('duplicate array item')
        for child in value:
            validate(child, rule['items'])
    elif kind == 'string':
        if type(value) is not str or not rule.get('minLength', 0) <= len(value) <= rule.get('maxLength', 1000000):
            raise ValueError('invalid string')
        value.encode('utf-8', errors='strict')
        if 'pattern' in rule and not re.fullmatch(rule['pattern'], value):
            raise ValueError('invalid pattern')
    elif kind == 'integer':
        if type(value) is not int or not rule['minimum'] <= value <= rule['maximum']:
            raise ValueError('invalid integer')
    elif kind == 'boolean':
        if type(value) is not bool:
            raise ValueError('invalid boolean')
    else:
        raise ValueError('unsupported schema keyword/type')
    if 'const' in rule and value != rule['const']:
        raise ValueError('invalid constant')


def artifact(ref, context, kind):
    row = context['ledger'].get(ref['id'])
    if row is None or row['kind'] != kind or row['projectId'] != context['binding']['projectId'] \
            or row['runId'] != context['binding']['childRunId'] or row.get('isLink', False):
        raise ValueError('invalid authoritative artifact ownership/type')
    if kind == 'CONTROL_REPORT' and row['producer'] != 'CONTROL_VERIFIER':
        raise ValueError('model cannot issue verification evidence')
    file = Path(row['path'])
    if file.is_symlink():
        raise ValueError('linked evidence')
    raw = file.read_bytes()
    actual = dict(id=ref['id'], sizeBytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
    if actual != ref or actual['sha256'] != row['sha256'] or actual['sizeBytes'] != row['sizeBytes']:
        raise ValueError('artifact actual bytes mismatch')
    return raw


def accept(claim, context):
    try:
        validate(claim, schema())
        if not context['confirmed'] or context['cancelRequested'] or context['mode'] != 'EXECUTABLE_CHECKS':
            return False
        if claim['binding'] != context['binding'] or claim['execution'] != context['execution']:
            return False
        if any(context['registered'][key] != claim['binding'][key] for key in REGISTERED):
            return False
        if sorted(claim['checkRefs']) != sorted(context['checkRefs']):
            return False
        if hashlib.sha256(Path(context['verifierPath']).read_bytes()).hexdigest() != claim['binding']['verifierSourceDigest']:
            return False
        execution = context['execution']
        if execution['exitCode'] != 0 or execution['timedOut'] or execution['cancelled'] or not execution['cleanupConfirmed']:
            return False
        report = parse(artifact(claim['reportRef'], context, 'CONTROL_REPORT').decode('utf-8-sig'))
        required = {'version', 'status', 'testsDiscovered', 'testsPassed', 'testsSkipped', 'failed', 'errors'}
        if set(report) != required or report['version'] != 'm1b-reference-report-v1' or report['status'] != 'PASSED':
            return False
        counts = [report[k] for k in ['testsDiscovered', 'testsPassed', 'testsSkipped', 'failed', 'errors']]
        if any(type(n) is not int or n < 0 for n in counts):
            return False
        discovered, passed, skipped, failed, errors = counts
        if discovered != passed + skipped + failed + errors or discovered < 1 \
                or passed < context['minPassed'] or failed != 0 or errors != 0:
            return False
        if [r['id'] for r in claim['outputs']] != context['outputIds']:
            return False
        for ref in claim['outputs']:
            artifact(ref, context, 'DELIVERABLE')
        return True
    except (ValueError, KeyError, OSError, UnicodeError, TypeError):
        return False


def evaluate(case):
    return accept(case['claim'], case['context'])
