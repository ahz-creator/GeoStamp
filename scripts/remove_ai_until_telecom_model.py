from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]

# 1) Remove ML Kit AI dependencies so there is no generic detector in production.
gradle = root / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = re.sub(r'\n\s*// Free on-device AI object counting\. No per-photo cloud/API quota\.\n\s*implementation \'com\.google\.mlkit:object-detection:[^\']+\'\n\s*implementation \'com\.google\.mlkit:image-labeling:[^\']+\'\n', '\n', s)
s = re.sub(r'\n\s*implementation \'com\.google\.mlkit:object-detection:[^\']+\'\n?', '\n', s)
s = re.sub(r'\n\s*implementation \'com\.google\.mlkit:image-labeling:[^\']+\'\n?', '\n', s)
gradle.write_text(s, encoding='utf-8')

# 2) Remove AI execution and AI fields from capture metadata.
vm = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
s = vm.read_text(encoding='utf-8')
# Handles both old thumbnail-based caller and latest analyzeFile caller.
s = re.sub(
    r'\n\s*val aiVisual = withContext\(Dispatchers\.IO\) \{.*?\n\s*\}\n\s*val slipSession',
    '\n                val slipSession',
    s,
    flags=re.S,
)
s = re.sub(r'\n\s*put\("aiVisualSummary",\s*aiVisual\.summary\)', '', s)
s = re.sub(r'\n\s*put\("aiObjectCountSummary",\s*aiVisual\.summary\)', '', s)
s = re.sub(r'\n\s*put\("aiVisualSummaryStatus",\s*aiVisual\.status\)', '', s)
s = re.sub(r'\n\s*put\("aiVisualSummaryProvider",\s*aiVisual\.provider\)', '', s)
s = re.sub(r'\n\s*put\("aiVisualPurpose",.*?\)', '', s)
vm.write_text(s, encoding='utf-8')

# 3) Remove AI from PDF/report. Supports both the current 2-page closure layout
# and the earlier one-page exporter.
pdf = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'
s = pdf.read_text(encoding='utf-8')

# Latest 2-page section: remove complete AI band between AI section and FIELD SESSION.
s = re.sub(
    r'\n\s*val aiY=.*?\n\s*val sY=',
    '\n        val sY=',
    s,
    flags=re.S,
)
# Earlier one-page AI band.
s = re.sub(
    r'\n\s*// AI summary: descriptive only.*?\n\s*// Field session context',
    '\n\n        // Field session context',
    s,
    flags=re.S,
)
# Remove any remaining AI-specific local variables/section labels if present.
s = re.sub(r'\n\s*val ai\s*=.*', '', s)
s = re.sub(r'\n\s*val purpose\s*=.*', '', s)
s = s.replace('AI-DETECTED OBJECTS', 'VISUAL EVIDENCE')
s = s.replace('AI OBJECT COUNT', 'VISUAL EVIDENCE')
# Remove AI disclaimers/scope claims entirely.
s = re.sub(r'\s*AI observations are informational[^"\n]*', '', s, flags=re.I)
s = re.sub(r'\s*AI-detected objects are a visual inventory only[^"\n]*', '', s, flags=re.I)
s = re.sub(r'\s*AI object detection/count only[^"\n]*', '', s, flags=re.I)
s = s.replace('Automated visual inventory only · not used to determine authenticity.', '')
s = s.replace('Automated visual inventory only - excluded from authentication PASS/FAIL.', '')
# Renumber visible page-one sections after removing AI.
s = s.replace('03  FIELD SESSION', '02  FIELD SESSION')
s = s.replace('04  AUTHENTICATION RESULT', '03  AUTHENTICATION RESULT')
# Technical annex numbering follows page one with no AI section.
s = s.replace('05  MACHINE VERIFICATION MATRIX', '04  MACHINE VERIFICATION MATRIX')
s = s.replace('06  CRYPTOGRAPHIC & PROVENANCE RECORD', '05  CRYPTOGRAPHIC & PROVENANCE RECORD')
s = s.replace('07  EVIDENCE LIFECYCLE', '06  EVIDENCE LIFECYCLE')
s = s.replace('08  METHOD & SCOPE', '07  METHOD & SCOPE')
pdf.write_text(s, encoding='utf-8')

# 4) Remove AI from mobile verification receipt if any current/local revision contains it.
verify = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/VerifyEvidenceActivity.kt'
if verify.exists():
    s = verify.read_text(encoding='utf-8')
    # Remove simple blocks/lines referring to AI fields or AI labels.
    lines = []
    skip = 0
    for line in s.splitlines():
        low = line.lower()
        if any(k in low for k in ['aiobjectcountsummary','aivisualsummary','ai-detected objects','ai object count','ai objects']):
            continue
        lines.append(line)
    verify.write_text('\n'.join(lines) + '\n', encoding='utf-8')

# 5) Keep source history simple: replace AI client with a dependency-free disabled stub
# instead of deleting the file (safer for any stale branch/import reference).
ai = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/AiVisualSummaryClient.kt'
if ai.exists():
    ai.write_text('''package com.axiominfratech.geostamp.verification\n\n/**\n * AI visual analysis is intentionally disabled.\n * Re-enable only with a validated telecom-specific model.\n */\nobject AiVisualSummaryClient {\n    const val ENABLED = false\n}\n''', encoding='utf-8')

print('AI removed from capture, metadata, receipt and forensic reports. ML Kit dependencies removed. Telecom-specific AI can be added later as a separate validated feature.')
