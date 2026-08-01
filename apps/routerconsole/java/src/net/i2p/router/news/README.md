# News (`news/`)

Parses the I2P news format — Atom with additional metadata for the update-notification feature. In the standard router this is a console function, but it lives in its own package so it can also be bundled in Android builds.

## How It Works

`NewsManager` coordinates fetching and storage of news; `NewsXMLParser` parses Atom feeds into `NewsEntry` objects with update-related metadata; `PersistNews` stores them for display; `RFC3339Date` handles the Atom timestamp format.

## Key Classes

| Class               | Purpose                                      |
| ------------------- | -------------------------------------------- |
| `NewsManager`       | Coordinates news fetching and storage        |
| `NewsXMLParser`     | Parses Atom news feeds                       |
| `NewsEntry`         | A single news item                           |
| `NewsMetadata`      | Update-related news metadata                 |
| `PersistNews`       | Persists news entries to disk                |
| `RFC3339Date`       | Atom RFC 3339 timestamp handling             |
| `BlocklistEntries`  | Blocklist entries in news feeds              |

## Docs

- [package.html](package.html) — canonical package description
- [`routerconsole/README.md`](../../../README.md) — console module overview
