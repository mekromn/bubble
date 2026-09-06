"""Print bounded synthetic runtime diagnostics; no user sessions are used by CI."""
from pathlib import Path
import xml.etree.ElementTree as ET

for path in sorted(Path('evidence').glob('*')):
    if path.is_file() and path.suffix == '.txt' and path.name.startswith(('files-', 'upload-', 'download-')):
        print('\n--- ' + path.name + ' ---', flush=True)
        print(path.read_text(errors='replace')[:60000], flush=True)
counts = dict(tests=0, failures=0, errors=0, skipped=0)
for path in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    root = ET.parse(path).getroot()
    for key in counts:
        counts[key] += int(root.get(key, '0'))
print('UNIT_TEST_COUNTS=' + str(counts), flush=True)
lint = Path('app/build/reports/lint-results-debug.xml')
if lint.exists():
    issues = ET.parse(lint).getroot().findall('issue')
    print('LINT_COUNTS=' + str({level: sum(i.get('severity') == level for i in issues) for level in ('Error', 'Warning')}), flush=True)
