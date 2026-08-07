from pathlib import Path

p = Path('app/src/main/java/com/axiominfratech/geostamp/ui/VerifyEvidenceActivity.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('registryBacked && !hasMandatoryVisual', 'registryBacked && !hasVisual')
s = s.replace('if (hasMandatoryVisual) View.VISIBLE else View.GONE', 'if (hasVisual) View.VISIBLE else View.GONE')
p.write_text(s, encoding='utf-8')
print('Fixed VerifyEvidenceActivity: hasMandatoryVisual -> hasVisual')
