from pathlib import Path

root = Path(__file__).resolve().parents[1]

# Phase A: ensure Android uses adaptive launcher resources instead of the legacy drawable directly.
manifest = root / 'app/src/main/AndroidManifest.xml'
s = manifest.read_text(encoding='utf-8')
s = s.replace('android:icon="@drawable/geostamp_app_icon"', 'android:icon="@mipmap/ic_launcher"')
s = s.replace('android:roundIcon="@drawable/geostamp_app_icon"', 'android:roundIcon="@mipmap/ic_launcher_round"')
manifest.write_text(s, encoding='utf-8')

# Adaptive icon XML. Existing geostamp_app_icon remains the artwork source so the approved image
# can be replaced once without touching manifest/code.
for folder in ['mipmap-anydpi-v26']:
    d = root / 'app/src/main/res' / folder
    d.mkdir(parents=True, exist_ok=True)
    xml = '''<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@color/geostamp_icon_background" />\n    <foreground android:drawable="@drawable/geostamp_app_icon" />\n</adaptive-icon>\n'''
    (d/'ic_launcher.xml').write_text(xml, encoding='utf-8')
    (d/'ic_launcher_round.xml').write_text(xml, encoding='utf-8')

values = root/'app/src/main/res/values/geostamp_icon.xml'
values.write_text('''<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="geostamp_icon_background">#FFFFFF</color>\n</resources>\n''', encoding='utf-8')

# Legacy devices: point mipmap aliases to the approved drawable.
for density in ['mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi']:
    d = root/'app/src/main/res'/f'mipmap-{density}'
    d.mkdir(parents=True, exist_ok=True)
    alias = '''<?xml version="1.0" encoding="utf-8"?>\n<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n    <item android:drawable="@drawable/geostamp_app_icon" />\n</layer-list>\n'''
    (d/'ic_launcher.xml').write_text(alias, encoding='utf-8')
    (d/'ic_launcher_round.xml').write_text(alias, encoding='utf-8')

# Phase B: save captures in useful Gallery folders: Organization/Site, while personal captures
# stay isolated. This is storage organization only; cryptographic metadata remains unchanged.
vm = root/'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
s = vm.read_text(encoding='utf-8')
s = s.replace('val saved = saveToGalleryInternal(stampedFile)', 'val saved = saveToGalleryInternal(stampedFile, operatorStr, siteIdStr, isPersonal)')
s = s.replace('private suspend fun saveToGalleryInternal(file: File): Boolean = withContext(Dispatchers.IO) {', '''private suspend fun saveToGalleryInternal(\n        file: File,\n        operatorName: String,\n        siteId: String,\n        isPersonal: Boolean\n    ): Boolean = withContext(Dispatchers.IO) {''')
s = s.replace('put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeoStamp")', '''val safeOperator = operatorName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Organization" }\n                    val safeSite = siteId.removePrefix("~").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Unassigned" }\n                    val relativeFolder = if (isPersonal)\n                        "Pictures/GeoStamp/Personal"\n                    else\n                        "Pictures/GeoStamp/$safeOperator/$safeSite"\n                    put(MediaStore.Images.Media.RELATIVE_PATH, relativeFolder)''')
s = s.replace('val folder = File(pics, "GeoStamp")', '''val safeOperator = operatorName.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Organization" }\n                val safeSite = siteId.removePrefix("~").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Unassigned" }\n                val folder = if (isPersonal)\n                    File(pics, "GeoStamp/Personal")\n                else\n                    File(pics, "GeoStamp/$safeOperator/$safeSite")''')
vm.write_text(s, encoding='utf-8')

print('Applied combined phase: adaptive launcher icon wiring + organization/site gallery folders.')
