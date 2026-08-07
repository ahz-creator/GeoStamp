from pathlib import Path

root = Path(__file__).resolve().parents[1]
layout = root/'app/src/main/res/layout/activity_verify_evidence.xml'
activity = root/'app/src/main/java/com/axiominfratech/geostamp/ui/VerifyEvidenceActivity.kt'
pdf = root/'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'

# ---- Mobile slip: compact receipt proportions, color photo first, operational facts only ----
s = layout.read_text(encoding='utf-8')
s = s.replace('android:layout_height="150dp"\n                    android:background="#E9EEF4"', 'android:layout_height="118dp"\n                    android:background="#E9EEF4"')
s = s.replace('android:layout_height="52dp"\n                    android:gravity="center"', 'android:layout_height="38dp"\n                    android:gravity="center"')
s = s.replace('android:padding="14dp"', 'android:padding="12dp"')
s = s.replace('android:textSize="19sp"', 'android:textSize="17sp"')
s = s.replace('android:textSize="12sp" />', 'android:textSize="11sp" />', 1)
s = s.replace('android:text="SHARE PDF REPORT"', 'android:text="SHARE REPORT · PDF"')
s = s.replace('android:layout_height="46dp"\n                        android:layout_marginTop="12dp"', 'android:layout_height="44dp"\n                        android:layout_marginTop="10dp"')
layout.write_text(s, encoding='utf-8')

# ---- Activity: make the slip more field-oriented, include session interval wording ----
s = activity.read_text(encoding='utf-8')
old = '''        binding.tvResultDetails.text = buildString {\n            append("EVIDENCE ID\\n$id\\n\\n")\n            append("CAPTURED\\n${formatTime(capturedAt)}\\n\\n")\n            append("OPERATOR / PROJECT\\n$primary\\n\\n")\n            append("SITE / REFERENCE\\n$secondary\\n\\n")\n            append("LOCATION\\n$location\\n\\n")\n            append("DEVICE\\n$device · $maskedDevice")\n        }'''
new = '''        binding.tvResultDetails.text = buildString {\n            append("EVIDENCE  •  $id\\n")\n            append("CAPTURED  •  ${formatTime(capturedAt)}\\n")\n            append("OPERATOR  •  $primary\\n")\n            append("SITE  •  $secondary\\n")\n            append("GPS  •  $location\\n")\n            append("DEVICE  •  $device · $maskedDevice")\n        }'''
s = s.replace(old, new)
old2 = '''        binding.tvSessionActivity.text = buildString {\n            append("SESSION ACTIVITY\\n")\n            append("Clock-in: ${formatTime(sessionStarted)}\\n")\n            append("Reference: ${formatTime(capturedAt)} · $siteId\\n")\n            append("Before: ${displayCount(beforeSite)}   After: ${displayCount(afterSite)}   Site total: ${displayCount(totalSite)}\\n")\n            append("Session total: ${displayCount(totalSession)}   Sites visited: ${displayCount(sitesVisited)}")\n        }'''
new2 = '''        binding.tvSessionActivity.text = buildString {\n            append("THIS FIELD SESSION\\n")\n            append("Clock-in ${formatTime(sessionStarted)}  •  Reference ${formatTime(capturedAt)}\\n")\n            append("At $siteId: ${displayCount(beforeSite)} before  •  ${displayCount(afterSite)} after  •  ${displayCount(totalSite)} total\\n")\n            append("Entire session: ${displayCount(totalSession)} photos  •  ${displayCount(sitesVisited)} sites")\n        }'''
s = s.replace(old2, new2)
s = s.replace('binding.btnViewReport.text = "SHARE PDF REPORT"', 'binding.btnViewReport.text = "SHARE REPORT · PDF"')
activity.write_text(s, encoding='utf-8')

# ---- PDF: strengthen report wording and add reference-relative session facts ----
s = pdf.read_text(encoding='utf-8')
s = s.replace('"MOBILE PDF REPORT"', '"FORENSIC EVIDENCE REPORT"')
s = s.replace('"Photos before at site" to count(record, "sitePhotosBefore", "photosBeforeAtSite"),', '"Photos before this evidence (same site)" to count(record, "sitePhotosBefore", "photosBeforeAtSite"),')
s = s.replace('"Photos after at site" to count(record, "sitePhotosAfter", "photosAfterAtSite"),', '"Photos after this evidence (same site)" to count(record, "sitePhotosAfter", "photosAfterAtSite"),')
s = s.replace('"Total at site" to count(record, "siteSessionPhotoTotal", "sitePhotoTotal"),', '"Total photos at this site in session" to count(record, "siteSessionPhotoTotal", "sitePhotoTotal"),')
s = s.replace('"Operator-session total" to count(record, "operatorSessionPhotoTotal", "sessionPhotoTotal"),', '"Total photos in operator session" to count(record, "operatorSessionPhotoTotal", "sessionPhotoTotal"),')
s = s.replace('"Sites visited" to count(record, "operatorSessionSitesVisited", "sessionSitesVisited"),', '"Sites visited in operator session" to count(record, "operatorSessionSitesVisited", "sessionSitesVisited"),')
# Add distance/radius lines if not already present.
needle = '"Clock-out reason" to record.optString("operatorSessionClockOutReason", "Active / pending")'
replacement = '''"Clock-out reason" to record.optString("operatorSessionClockOutReason", "Active / pending"),\n            "Distance from matched site at capture" to distance(record),\n            "Applied site radius" to radius(record)'''
s = s.replace(needle, replacement)
insert_before = '''    private fun count(record: JSONObject, first: String, second: String): String {'''
helpers = '''    private fun distance(record: JSONObject): String {\n        val value = record.optDouble("siteDistanceM", record.optDouble("distanceM", Double.NaN))\n        return if (value.isFinite()) "%.1f m".format(Locale.US, value) else "Unavailable"\n    }\n\n    private fun radius(record: JSONObject): String {\n        val value = record.optDouble("siteRadiusM", Double.NaN)\n        return if (value.isFinite()) "%.0f m".format(Locale.US, value) else "Unavailable"\n    }\n\n'''
if helpers.strip() not in s:
    s = s.replace(insert_before, helpers + insert_before)
pdf.write_text(s, encoding='utf-8')

print('Applied combined phase: compact mobile verification slip + WhatsApp forensic PDF report.')
