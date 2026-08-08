from pathlib import Path

root = Path(__file__).resolve().parents[1]
kt = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'

s = kt.read_text(encoding='utf-8')

# Kotlin compile hotfix: cell() signature is
# (..., boldValue:Boolean, valueColor:Int=TEXT, mono:Boolean=false)
# The first deployment script passed `true` positionally where valueColor:Int
# was expected on the two monospace fingerprint cells.
replacements = {
    'cell(c,x[0],y,x[1]-x[0],31f,"IMAGE SHA-256 FINGERPRINT",first(r.optString("imageSha256"),"Unavailable"),false,true)':
    'cell(c,x[0],y,x[1]-x[0],31f,"IMAGE SHA-256 FINGERPRINT",first(r.optString("imageSha256"),"Unavailable"),false,TEXT,true)',

    'cell(c,x[2],y,x[3]-x[2],31f,"KEY FINGERPRINT",first(r.optString("captureKeyFingerprint"),"Unavailable"),false,true)':
    'cell(c,x[2],y,x[3]-x[2],31f,"KEY FINGERPRINT",first(r.optString("captureKeyFingerprint"),"Unavailable"),false,TEXT,true)',
}

changed = 0
for old, new in replacements.items():
    if old in s:
        s = s.replace(old, new)
        changed += 1

# Also make any equivalent future calls unambiguous by using named args.
s = s.replace(
    'false,TEXT,true)',
    'boldValue=false, valueColor=TEXT, mono=true)'
)

kt.write_text(s, encoding='utf-8')
print(f'Forensic certificate compile fix applied ({changed} fingerprint call(s) corrected).')
