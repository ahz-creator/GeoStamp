#!/usr/bin/env python3
"""GeoStamp P22/P23 release audit. No network access; scans source/config only."""
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
errors=[]; warnings=[]

text_files=[]
for p in ROOT.rglob('*'):
    if p.is_file() and '.git' not in p.parts and 'build' not in p.parts and p.stat().st_size < 2_000_000:
        if p.suffix.lower() in {'.kt','.kts','.gradle','.properties','.xml','.json','.txt','.md','.java','.py','.gs'}:
            try: text_files.append((p,p.read_text(errors='ignore')))
            except Exception: pass

for p,s in text_files:
    # Hard-coded private credentials/tokens. Public URLs are allowed.
    for pat,label in [
        (r'(?i)(api[_-]?key|secret|token|password)\s*[=:]\s*["\'][^"\']{12,}["\']','possible hard-coded credential'),
        (r'(?i)BEGIN (RSA|EC|OPENSSH|PRIVATE) KEY','private key material'),
    ]:
        if re.search(pat,s):
            warnings.append(f'{label}: {p.relative_to(ROOT)}')

build=(ROOT/'app/build.gradle').read_text(errors='ignore')
if 'signingConfig signingConfigs.debug' in build:
    errors.append('Release build is still signed with the debug keystore.')
if 'minifyEnabled true' not in build:
    errors.append('Release minification is disabled.')
if 'shrinkResources true' not in build:
    warnings.append('Release resource shrinking is disabled.')

manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text(errors='ignore')
if 'allowBackup="false"' not in manifest:
    errors.append('Android backup is not explicitly disabled.')

rules=(ROOT/'app/src/main/res/xml/data_extraction_rules.xml').read_text(errors='ignore')
for path in ['evidence_registry_outbox','evidence_registry_published']:
    if path not in rules:
        errors.append(f'Backup exclusion missing for {path}.')

if not (ROOT/'gradle/wrapper/gradle-wrapper.jar').exists():
    warnings.append('gradle-wrapper.jar is missing; wrapper build cannot run in this source package.')

print('GeoStamp P22/P23 RELEASE AUDIT')
print('='*32)
for x in errors: print('ERROR:',x)
for x in warnings: print('WARN :',x)
print('STATUS:', 'FAIL' if errors else 'PASS WITH WARNINGS' if warnings else 'PASS')
sys.exit(1 if errors else 0)
