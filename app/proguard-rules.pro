# GeoStamp release rules. Keep JSON field names used by forensic records.
-keep class com.axiominfratech.geostamp.verification.** { *; }
-keep class com.axiominfratech.geostamp.forensics.** { *; }
-dontwarn org.bouncycastle.**
