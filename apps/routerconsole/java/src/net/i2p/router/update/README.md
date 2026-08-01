# Router Update (`update/`)

The router update process: checking for, downloading, verifying, and applying I2P router updates, plus the update news and notification handling.

## How It Works

Update checking and applying runs through the console. Updates are downloaded, signature-verified, and applied to the router installation; plugin updates are handled separately. The update process integrates with the news feed to notify users of available updates.

## Key Classes

| Class                          | Purpose                                             |
| ------------------------------ | --------------------------------------------------- |
| `ConsoleUpdateManager`         | Coordinates router update checking and applying     |
| `DevSU3UpdateChecker`          | Checks for developer (unsigned) SU3 updates         |
| `DevSU3UpdateRunner`           | Applies a developer SU3 update                      |
| `NewsFetcher` / `NewsHandler`  | Fetches and processes update news                   |
| `PluginUpdateChecker`          | Checks for plugin updates                           |
| `PluginUpdateRunner`           | Applies plugin updates                              |
| `TrustedPluginKeys`            | Validates plugin update signatures                  |

## Docs

- [package.html](package.html) — canonical package description
- [`routerconsole/README.md`](../../../README.md) — console module overview
- Update interfaces without router context: `net.i2p.update` in core
