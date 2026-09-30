# GeoNames assets — Phase 3 preparation notes

Inspected 2026-09-30 during Phase 2. The dataset was **not modified** in any
way (per Phase 2 rules: do not delete, replace, rebuild, or add API deps).

## What exists

`app/src/main/assets/geonames/` contains exactly two files:

| File | Size | Contents |
|---|---|---|
| `README.md` | — | Provenance + license notes |
| `cities.tsv` | ~1.5 MB | 34,152 cities, tab-separated, **no header row** |

### `cities.tsv` column layout (verified)

```
name \t latitude \t longitude \t country \t population
```

Example rows:

```
Shanghai    31.22222    121.45806    China    24874500
Chongqing   29.56026    106.55771    China    22000000
```

Properties verified by inspection:

- Derived from GeoNames `cities15000.txt` + `countryInfo.txt`
  (https://download.geonames.org/export/dump/), keeping only `name`,
  `latitude`, `longitude`, resolved country name, and `population`.
- Sorted by **population descending** (largest cities first).
- **Zero rows** with missing/blank latitude or longitude.
- Coordinates are decimal degrees, already validated ranges in the sample.
- License: **CC-BY 4.0** — attribution is already shown in the app and on
  every rendered frame (see README.md).

## Recommended Phase 3 integration approach

Goal: resolve a timeline coordinate → nearest city name, fully offline.

1. **One-time load, lazily and off the UI thread.** Parse the TSV once
   (e.g. in a `GeoNamesRepository` singleton) into compact primitive arrays
   (`DoubleArray` lat/lng, `Array<String>` names, `IntArray` population).
   ~34k rows is small: a few MB in memory, no database needed.
2. **Spatial index for nearest-city queries.** 34k points is small enough
   that even a coarse uniform grid (e.g. 1°×1° cells → city index lists)
   gives effectively O(1) candidate lookup; a KD-tree is the textbook
   alternative if grid edge cases (poles, antimeridian) become annoying.
   Do **not** add a SQLite/FTS dependency for this — the TSV + in-memory
   index is already fast and keeps the app offline.
3. **Population as tie-breaker.** Because rows are sorted by population
   descending, the first hit within a radius is usually the most useful
   label ("Lahore" rather than a suburb). Keep that ordering in the index.
4. **Radius + fallback.** Query nearest city within ~50 km; beyond that,
   fall back to "Unknown location" (or country-level label) rather than
   inventing a misleading name. Never fabricate coordinates.
5. **Cache results.** Timeline points repeat (dwell points); memoize
   coordinate→name lookups in an LRU keyed by rounded lat/lng.
6. **Privacy preserved.** Everything stays on-device; no network geocoding.
   Keep the existing "no raw coordinates in Logcat" rule.

## Non-goals for Phase 3

- Do not re-download or regenerate the dataset.
- Do not add a remote geocoding API.
- Do not change the CC-BY 4.0 attribution behavior.

---

# Phase 3 implementation (2026-09-30)

## Engine (`app/.../data/geo/`)

| File | Role |
|---|---|
| `GeoModels.kt` | `GeoCity`, `ResolvedLocation`, `MatchQuality` (CLOSE/APPROXIMATE/UNRESOLVED), `ResolvedPoint` (raw `TrackPoint` + resolved metadata), `LocationResolutionConfig`, `unresolvedLocation()` |
| `GeoGridIndex.kt` | 1°×1° grid over the 34,152 cities; `forEachInRadius` with cos(latitude) longitude widening + antimeridian wrap |
| `GeoNamesRepository.kt` | Session singleton; parses the TSV once (background thread, lazy — never at startup); `create(reader)` seam for JVM tests |
| `LocationResolver.kt` | `resolve(lat,lng)`, `resolveAll(points, onProgress)` (dedup by 3-decimal cache key, LRU cache of matched city index, distance recomputed per exact query), Haversine via existing `haversineM` |

Key behaviors:
- **Distance is primary.** Nearest place wins; population only breaks ties
  within `tieBreakerEpsilonKm` (2 km) — a town 2 km away beats a city 25 km away.
- **Thresholds** (`LocationResolutionConfig`): CLOSE ≤ 25 km, UNRESOLVED > 100 km,
  chosen against the real dataset density (cities15000-scale; nearest place in
  populated regions is usually within a few km).
- **No invented fields.** The dataset has no region/admin codes, country codes,
  feature classes or GeoName IDs, so the model exposes only name/country/distance.
- **Privacy:** everything local; no network, no logging of coordinates.
- Legacy `CityDatabase`/`VisitedCities` (pre-existing export screens) left untouched.

## UI wiring

- Import flow gained a real 5th stage, **Resolving location names**, with honest
  fractional progress from `resolveAll` (Reading 0.05 → Detecting 0.15 →
  Parsing 0.20–0.75 → Resolving 0.76–0.94 → Finalizing 0.95).
- `ImportSummary.resolvedPoints: List<ResolvedPoint>` is the Phase 4 handoff
  (raw coordinates preserved; the future map engine never parses TSV).
- File Summary shows "Locations named: N / M".
- Timeline Preview event rows show resolved city + country (+ "≈" for
  approximate, "Location unavailable" otherwise); tapping a real pin opens the
  reusable `LocationDetailCard` dialog (name, country, exact coordinates,
  distance note, status chip).

## Tests

`app/src/test/.../geo/GeoNamesResolverTest.kt` — 18 tests: synthetic-dataset
algorithm tests (near/approximate/far, distance-vs-population, tie-break,
antimeridian, high latitude, invalid input, empty/malformed data, cache,
batch dedup + order + progress + raw-coordinate integrity) plus real-asset
tests (13 countries across 6 continents, mid-ocean unresolved, scale +
lookup performance). Real-asset tests skip gracefully if the working
directory is not the `app/` module dir.

## Known limitations

- No region/admin subdivision (not in dataset); country is the full name,
  no ISO code (not in dataset).
- Single global distance threshold; very sparse regions (Sahara, Siberia)
  resolve approximately or not at all by design.
- Tests were written but **not executed** — the sandbox cannot run Gradle
  (loopback TCP mangling); Sara builds and runs them in Android Studio.
