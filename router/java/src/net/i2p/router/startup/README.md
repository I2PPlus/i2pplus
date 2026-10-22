# Startup (`startup/`)

The router boot sequence: loading configuration, creating or rebuilding the RouterInfo (keypairs), and starting each subsystem in order.

## Boot order

| Job                        | Role                                                                   |
| -------------------------- | ---------------------------------------------------------------------- |
| `StartupJob`               | Entry point of the sequence                                            |
| `LoadClientAppsJob`        | Starts clients, queues delayed client jobs, starts the stats publisher |
| `LoadRouterInfoJob`        | Loads the RouterInfo from disk                                         |
| `RebuildRouterInfoJob`     | Rebuilds the RouterInfo if necessary                                   |
| `CreateRouterInfoJob`      | Creates the RouterInfo (keypairs) if necessary                         |
| `BootCommSystemJob`        | Starts the comm system                                                 |
| `BootNetworkDbJob`         | Starts the netdb                                                       |
| `BootPeerManagerJob`       | Starts the peer manager, then the tunnel manager                       |
| `BuildTrustedLinksJob`     | Scheduled at boot; currently a no-op, kept for restricted routes       |
| `StartAcceptingClientsJob` | Starts the client manager                                              |

Other classes: `RouterAppManager` (starts bundled apps), `WorkingDir` / `PortableWorkingDir` (data directory resolution), `ClientAppConfig` (app config loading), `MigrateJetty` (legacy Jetty migration).

`ReadConfigJob` (in `tasks/`) then rereads `router.config` when it changes: every
60 seconds normally, every 90 seconds in advanced mode.

## Docs

- [package.html](package.html) — canonical package description
