# Tasks (`tasks/`)

Periodic maintenance tasks, background jobs, and system upkeep that keep the router running.

## Key Classes

| Class                | Purpose                                                 |
| -------------------- | ------------------------------------------------------- |
| `RouterWatchdog`     | Monitors router health and recovery                     |
| `GracefulShutdown`   | Coordinates clean router shutdown                       |
| `Restarter`          | Performs soft router restart                            |
| `InstallUpdate`      | Handles software update installation                    |
| `ReadConfigJob`      | Reloads router configuration                            |
| `Republish`          | Periodically publishes router info                      |
| `CoalesceStatsEvent` | Aggregates and processes statistics                     |
| `CryptoChecker`      | Checks crypto algorithm availability                    |
| `MarkLiveliness`     | Periodic liveliness marker for multi-instance detection |
| `OOMListener`        | Handles out-of-memory conditions                        |
| `ThreadDump`         | Generates thread dumps for diagnostics                  |
| `ShutdownHook`       | JVM shutdown cleanup                                    |
| `BasePerms`          | Fixes permissions (Windows)                             |

## Docs

- [package.html](package.html) — canonical package description
