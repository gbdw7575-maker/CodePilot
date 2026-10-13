import json
import runpy
import sys

# This trusted sample verifier is separate from the read-only candidate workspace.
cases = [("Hello, World!", "hello-world"), ("  A--B  ", "a-b"),
         ("!!!", ""), ("MiXeD__Case", "mixed-case")]
passed, errors = 0, 0
try:
    slugify = runpy.run_path('/workspace/app.py')['slugify']
    for source, expected in cases:
        try:
            passed += int(slugify(source) == expected)
        except Exception:
            errors += 1
except Exception:
    errors = len(cases)
report = dict(version='m1b-reference-report-v1', testsDiscovered=len(cases),
              testsPassed=passed, testsSkipped=0, failed=len(cases)-passed-errors,
              errors=errors, status='PASSED' if passed == len(cases) and errors == 0 else 'FAILED')
print(json.dumps(report, sort_keys=True, separators=(',', ':')))
sys.exit(0 if report['status'] == 'PASSED' else 1)
