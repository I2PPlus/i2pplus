# GeoIP Resources (`geoip/`)

The data files that let a router turn a peer's IP address into a country, a
continent, or an autonomous-system name. Everything here is copied into the
installed package's `geoip/` directory, which the router reads at startup;
`geoip.dir` points it somewhere else.

The lookups are not cosmetic. Country data decides which peers are rejected when
the country blocklist is in force, and it labels the peers page, the ban log and
the status page.

## Files

| File | Read by | Notes |
| --- | --- | --- |
| `GeoLite2-Country.mmdb.gz` | `GeoIP` | the country database. The name is historical: `makegeoip.sh` downloads db-ip.com's `dbip-country-lite` database and renames it, so no MaxMind product has been the source for some time |
| `db-ip-asn.mmdb.gz` | `GeoIP` | autonomous system numbers and organisation names, used by `getOrgName()`. **I2P+ only** — upstream ships no ASN database and has no `getASNBuildInfo()` |
| `countries.txt` | `GeoIP`, `I2PSSLSocketFactory` | country code to country name. Header says it was last derived from MaxMind GeoLite on 2011-12-08; it is not regenerated |
| `continents.txt` | `Zones` | country code to continent code, fetched 2015-04-21, used to pick the NTP pool zone. Not regenerated |
| `geoipv6-extras.csv` | nothing at runtime | an input for the `GeoIPv6` command-line generator. The file it produces, `geoipv6.dat.gz`, is still understood by `GeoIPv6` but is no longer shipped or generated |
| `makegeoip.sh` | release maintenance | regenerates the two `.mmdb.gz` files. Not run by the router or the installer |

A distribution-installed database is preferred over the shipped one when it is
newer: `/usr/share/GeoIP/GeoIP.dat` (or the path in the `geoip.dat` property) is
used if its modification time is later than the bundled database's.

## Refreshing the databases

    cd installer/resources/geoip
    ./makegeoip.sh [--host HOST] [--port PORT]

Downloads `dbip-country-lite-<YYYY-MM>.mmdb.gz` and `dbip-asn-lite-<YYYY-MM>.mmdb.gz`
from `download.db-ip.com/free/`, through the local proxy at `127.0.0.1:4444` by
default; `--host` and `--port` change that, and `HTTP_PROXY` is honoured. Needs
`curl`. The previous file is kept as `<name>.old`, and a download smaller than
2 MB is discarded with the old file restored — the point being not to commit a
truncated database. Both files are stored gzipped; the package gunzips them.

## Packaging

`build.xml` always copies `countries.txt` and `continents.txt` into the
package's `geoip/` directory. The databases are gunzipped into it by
`prepgeoupdate-unlesspkg` (both files) or `prepasnupdate` (the ASN database
alone). Set `with-geoip-database` in `override.properties` to leave them out:
Debian and Ubuntu builds have done so since 0.9.26, on the grounds that the
databases are large and go stale, relying instead on the distribution's own
`geoip-database` package.

## Config keys

| Key | Default | Effect |
| --- | --- | --- |
| `routerconsole.geoip.enable` | true | all GeoIP lookups |
| `geoip.dir` | `geoip` | directory the files are read from, relative to the router directory |
| `routerconsole.enableISPNameNormalization` | true | strips suffixes, abbreviates and title-cases organisation names read from the ASN database |

## I2P+ versus upstream

The whole `geoip/` subdirectory is a fork layout: upstream keeps
`makegeoip.sh`, `geoipv6-extras.csv` and `GeoLite2-Country.mmdb.gz` directly
under `installer/resources/`. Beyond the layout, the fork adds the ASN database
and its lookups, and the status page's `GeoIP Db:` and `ASN Db:` lines, which
report the build date of each database in use. Upstream's status page has
neither.
