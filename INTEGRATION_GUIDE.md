# GeoStamp v8 — Professional Overlay Integration Guide

## What Changed

### Problem (v7)
- Overlay was a small floating/draggable vertical card (272dp wide)
- No logo visible in live camera preview
- Saved image overlay didn't match the UI card
- Unstructured layout

### Solution (v8)
- Full-width horizontal stamp card pinned at bottom of camera preview
- Operator logo shown in live preview AND burned into saved photos
- Layout-weight driven columns for perfect proportions at any screen size
- BitmapOverlayHelper inflates the same XML for the saved image → pixel-perfect parity

---

## Architecture

```
Camera Preview
└── fragment_camera.xml
    └── <include layout="@layout/overlay_stamp_card"/>
            ↑ CameraFragment.updateStampCard() binds live data here
            ↑ Alpha driven by overlayAlpha stamp config

Capture Flow
└── CameraManager.captureAndStamp(StampData)
    ├── PRIMARY: BitmapOverlayHelper.render()
    │   └── Inflates overlay_stamp_card.xml at screen size
    │       → Renders to bitmap at screen resolution
    │       → Scales proportionally to photo size
    │       → Composites at bottom of photo
    └── FALLBACK: OverlayRenderer.render()  (canvas-based, always works)
```

---

## File Manifest — drop each file into the exact project path

| File (in this zip)                    | Destination in Android Studio project                                      |
|---------------------------------------|----------------------------------------------------------------------------|
| `layout/overlay_stamp_card.xml`       | `app/src/main/res/layout/overlay_stamp_card.xml`                           |
| `layout/fragment_camera.xml`          | `app/src/main/res/layout/fragment_camera.xml`  (**replace**)               |
| `drawable/bg_stamp_card.xml`          | `app/src/main/res/drawable/bg_stamp_card.xml`                              |
| `kotlin/BitmapOverlayHelper.kt`       | `app/src/main/java/com/axiominfratech/geostamp/overlay/BitmapOverlayHelper.kt` |
| `kotlin/OverlayRenderer.kt`           | `app/src/main/java/com/axiominfratech/geostamp/overlay/OverlayRenderer.kt` (**replace**) |
| `kotlin/CameraManager.kt`             | `app/src/main/java/com/axiominfratech/geostamp/camera/CameraManager.kt`    (**replace**) |
| `kotlin/CameraFragment.kt`            | `app/src/main/java/com/axiominfratech/geostamp/ui/CameraFragment.kt`       (**replace**) |
| `kotlin/MainViewModel.kt`             | `app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt`        (**replace**) |

---

## Operator Logos — IMPORTANT

The operator logos shipped in `drawable/operators/` must be correct:

| File                        | Required                                              |
|-----------------------------|-------------------------------------------------------|
| `operator_jazz.png`         | Jazz logo — yellow on dark red circle                 |
| `operator_telenor.png`      | **WHITE version** — cyan symbol + white text on black |
| `operator_ufone.png`        | Ufone logo (white bg acceptable)                      |
| `operator_zong.png`         | Zong logo — green/pink on black                       |

**To replace with uploaded PNG files:**
1. Copy `Jazz.png`           → `app/src/main/res/drawable/operators/operator_jazz.png`
2. Copy `telenor_white.png`  → `app/src/main/res/drawable/operators/operator_telenor.png`
3. Copy `Ufone.png`          → `app/src/main/res/drawable/operators/operator_ufone.png`
4. Copy `zong.png`           → `app/src/main/res/drawable/operators/operator_zong.png`

---

## Stamp Card Layout Columns

```
┌──────────┬──────────────┬─────────┬──────────┬──────────────┬──────────┬──────────┐
│  LOGO    │  📍 LOCATION │ 📡 SITE │ 👤 USER  │  📅 DATE     │ 🎯 ACC   │ 🧭 DIR   │
│ [image]  │  lat, lon    │   ID    │  name    │  & TIME      │ uracy    │ ection   │
│ [name]   │  address     │         │          │  time        │ quality  │ altitude │
└──────────┴──────────────┴─────────┴──────────┴──────────────┴──────────┴──────────┘
 weight 1.4    weight 2.0    1.3        1.3         1.6           1.2       1.3
```

---

## ViewBinding — stamp_overlay_card Access

Since the card is an `<include>` tag, ViewBinding generates `binding.stampOverlayCard` as
the root view of the included layout. Child views are accessed via `findViewById`:

```kotlin
val card = binding.stampOverlayCard
val logo = card.findViewById<ImageView>(R.id.img_operator_logo)
```

If your ViewBinding is already generating direct properties for the included views,
you can also use `binding.imgOperatorLogo` directly — depends on your Gradle version.
CameraFragment v2 uses the explicit `card.findViewById()` approach for safety.

---

## Key Design Decisions

### BitmapOverlayHelper vs OverlayRenderer

| | BitmapOverlayHelper | OverlayRenderer |
|---|---|---|
| Method | View inflation | Canvas drawing |
| Logo handling | Android ImageView (automatic) | Bitmap draw (manual) |
| Text scaling | sp → screen density (auto) | Manual proportional math |
| Match with UI | ✅ Pixel-perfect | ⚠️ Close but not identical |
| Thread safety | Main (view ops) + IO (file) | Any thread |
| Use case | Production primary path | Fallback |

### Logo Rendering
The logo is always rendered via `ImageView.setImageResource()` → `scaleType="fitCenter"`.
This means:
- Aspect ratio is always preserved ✅
- No pixelation (Android handles scaling) ✅
- For dark-background cards: Telenor white logo / Jazz (dark red circle) all visible ✅
- Ufone (white background): appears with white box — acceptable on dark card ✅

### Card Position in Saved Image
Card is placed at bottom of the photo:
- Left/right margin: 3% of image width
- Bottom margin: 1.5% of image height
- Card height: auto (scales with content at screen resolution)

---

## Troubleshooting

**Logo not showing in live preview:**
- Check that `operator_jazz.png` etc. exist in `drawable/operators/`
- Confirm `GeoStampApp.onCreate()` calls `OverlayRenderer.initLogos(this)`
- Confirm `CameraFragment.operatorLogoResId()` returns a valid resource ID

**Logo not showing in saved photo:**
- BitmapOverlayHelper calls `setImageResource()` which uses the same drawable files
- Check logcat for `BitmapOverlayHelper` errors — the fallback path will still save

**Card too small in saved photo:**
- Adjust `targetW = imgW * 0.94f` in `BitmapOverlayHelper.inflateAndRender()`
- Or adjust `cardH = imgH * 0.165f` in `OverlayRenderer.drawCard()`

**Text cut off:**
- Increase `layout_weight` of the offending column in `overlay_stamp_card.xml`
- Or decrease text size for that field
