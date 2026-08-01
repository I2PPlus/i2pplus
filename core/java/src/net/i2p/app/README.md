# App Framework (`app/`)

Interfaces for classes started and stopped via `clients.config`. Classes implementing `ClientApp` are controlled through that interface instead of being started with `main()`.

## How It Works

`ClientAppManager` (and its implementation) starts, stops, and tracks applications by lifecycle; each app receives its context via the constructor and reports state through `ClientAppState`. `NavService`, `NotificationService`, and `Outproxy` provide additional service interfaces for bundled apps.

## Key Classes

| Class                   | Purpose                                    |
| ----------------------- | ------------------------------------------ |
| `ClientApp`             | Lifecycle interface for bundled apps       |
| `ClientAppManager`      | Manages app start/stop and tracking        |
| `ClientAppManagerImpl`  | Default manager implementation             |
| `ClientAppState`        | App lifecycle state reporting              |
| `NavService`            | Navigation service interface               |
| `NotificationService`   | Notification interface                     |

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
