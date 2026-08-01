# Update (`update/`)

Interfaces and utilities for the update process that operate without a full router context — usable in standalone installations or update utilities.

## How It Works

`UpdateManager` and `Updater` define the update lifecycle; `Checker` performs update checks, `UpdateTask` runs the application step, and `UpdatePostProcessor` completes installation. `UpdateMethod` and `UpdateType` enumerate the supported update channels and mechanisms, and `Updater` handles version comparison and configuration parsing.

## Key Classes

| Class                 | Purpose                                            |
| --------------------- | -------------------------------------------------- |
| `UpdateManager`       | Update lifecycle management interface              |
| `Updater`             | Applies updates and manages version state          |
| `Checker`             | Performs update checks                             |
| `UpdateTask`          | Runs the update application step                   |
| `UpdatePostProcessor` | Completes installation after an update             |
| `UpdateMethod`        | Supported update channels (dev, signed, etc.)      |
| `UpdateType`          | Update kind (router, plugin, news)                 |

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
- Console update implementation: `net.i2p.router.update`
