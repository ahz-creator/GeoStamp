from pathlib import Path

root = Path(__file__).resolve().parents[1]
files = [
    root / 'app/src/main/java/com/axiominfratech/geostamp/ui/VerifyEvidenceActivity.kt',
    root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt',
]

for path in files:
    text = path.read_text(encoding='utf-8')
    old = '.substringAfter("base64,", "")'
    new = '.let { value -> if (value.contains("base64,")) value.substringAfter("base64,") else value }'
    if old not in text:
        print(f'No old decoder pattern found in {path.name}; skipping')
        continue
    text = text.replace(old, new)
    path.write_text(text, encoding='utf-8')
    print(f'Fixed plain/data-URI Base64 decoding in {path.name}')

print('Done. GeoStamp now accepts both raw /9j/... Base64 and data:image/jpeg;base64,... thumbnails.')
