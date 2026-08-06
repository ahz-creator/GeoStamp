# GeoStamp Combined Phase 10–12

This package contains changed Android files plus three zero-cost GitHub projects:

- `GeoStamp-Admin/` — static admin panel for operators and site CSV files.
- `GeoStamp-Config/` — public configuration repository consumed by Android.
- `GeoStamp-Portal/` — public receiver-side evidence verification portal.

## GitHub repositories

Create these public repositories under `ahz-creator`:

1. `GeoStamp-Config`
2. `GeoStamp-Admin`
3. `GeoStamp-Portal`

Upload each folder's contents to its matching repository. Enable GitHub Pages from the `main` branch/root for Admin and Portal.

Expected URLs:

- Admin: `https://ahz-creator.github.io/GeoStamp-Admin/`
- Portal: `https://ahz-creator.github.io/GeoStamp-Portal/`
- Config: `https://raw.githubusercontent.com/ahz-creator/GeoStamp-Config/main/config.json`

## Admin workflow

1. Open GeoStamp Admin.
2. Enter organization details.
3. Add/disable operators.
4. Attach one CSV per operator.
5. Download files or publish using a fine-grained GitHub token limited to `GeoStamp-Config`.
6. Android downloads the latest config and site lists, then keeps a local offline cache.

## CSV columns

Recommended header:

`site_id,operator,site_name,sector,latitude,longitude,city,province,technology`

## Android replacement files

Replace files at the exact paths included under `android/`.

After replacement: Sync Gradle → Clean Project → Rebuild Project.
