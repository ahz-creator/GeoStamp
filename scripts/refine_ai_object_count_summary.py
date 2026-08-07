from pathlib import Path

root = Path(__file__).resolve().parents[1]

# AI output is descriptive only: object names + visible counts.
client = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/AiVisualSummaryClient.kt'
s = client.read_text(encoding='utf-8')

s = s.replace(
'''                put("instruction", "Describe only visible, non-sensitive scene facts in 1-2 short sentences. Then state the likely field-documentation purpose in one short phrase. Do not identify people. Do not infer wrongdoing, ownership, safety compliance, or facts not visible in the image.")''',
'''                put("instruction", "Identify only clearly visible object types and count each visible instance. Return a short comma-separated list in the form 'Object name: count'. Do not describe the scene, infer purpose, identify people, infer ownership, condition, compliance, activity, or any fact not directly visible. If an object cannot be counted reliably, omit it.")'''
)

# Keep response compatibility while intentionally eliminating purpose.
s = s.replace(
'''                purpose = json.optString("purpose").trim().take(180),''',
'''                purpose = "",'''
)
client.write_text(s, encoding='utf-8')

# Persist a clearer object-count alias while keeping aiVisualSummary compatibility.
vm = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
s = vm.read_text(encoding='utf-8')
needle = '                        put("aiVisualSummary", aiVisual.summary)\n'
if needle in s and 'put("aiObjectCountSummary", aiVisual.summary)' not in s:
    s = s.replace(needle, needle + '                        put("aiObjectCountSummary", aiVisual.summary)\n', 1)
# Remove purpose persistence if previous phase added it.
s = s.replace('                        put("aiVisualPurpose", aiVisual.purpose)\n', '')
vm.write_text(s, encoding='utf-8')

# Report: rename section and show only object-count result, never likely purpose.
pdf = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'
s = pdf.read_text(encoding='utf-8')
s = s.replace(
'val ai = r.optString("aiVisualSummary").trim()',
'val ai = first(r.optString("aiObjectCountSummary"), r.optString("aiVisualSummary"), "").takeIf { it != "Unavailable" }.orEmpty().trim()'
)
# Purpose may exist in older records but must no longer be rendered.
s = s.replace(
'val purpose = first(r.optString("aiVisualPurpose"), r.optString("aiLikelyPurpose"), "").takeIf { it != "Unavailable" }.orEmpty().trim()',
'val purpose = ""'
)
s = s.replace('AI VISUAL SUMMARY', 'AI OBJECT COUNT')
s = s.replace(
'''            val summary = listOfNotNull(
                ai.takeIf { it.isNotBlank() },
                purpose.takeIf { it.isNotBlank() }?.let { "Likely documentation purpose: $it" }
            ).joinToString("  ")''',
'''            val summary = ai'''
)
s = s.replace(
'AI description only · excluded from PASS/FAIL authentication',
'AI object detection/count only · excluded from PASS/FAIL authentication'
)
pdf.write_text(s, encoding='utf-8')

print('Refined AI output to visible object names and counts only; removed purpose/scene narrative from new reports.')
