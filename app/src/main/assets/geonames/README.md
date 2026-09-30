# GeoNames city data

`cities.tsv` is derived from `cities15000.txt` and `countryInfo.txt` published by
[GeoNames](https://www.geonames.org) at https://download.geonames.org/export/dump/.

GeoNames data is licensed under **Creative Commons Attribution 4.0
(CC-BY 4.0)**. Attribution is shown in the app and on every rendered frame.

Processing: kept `name`, `latitude`, `longitude`, resolved country name, and
`population`; sorted by population descending; dropped to a compact TSV
(34,152 cities, ~1.4 MB) so the lookup stays fast and offline.
