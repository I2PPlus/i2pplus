# Statistics (`stat/`)

Statistical collection and analysis framework used throughout the router for adaptive operation, performance monitoring, and debugging.

## How It Works

`StatManager` collects `RateStat` entries, each composed of `Rate` measurements with configurable averaging periods and `Frequency` counters. The framework supports bandwidth tracking, message rates, processing times, health monitoring, and adaptive rate limiting based on observed load. `RateSummaryListener` exposes periodic summaries; `PersistenceHelper` persists stats across restarts.

## Key Classes

| Class                 | Purpose                                           |
| --------------------- | ------------------------------------------------- |
| `StatManager`         | Central statistics collection and management      |
| `RateStat`            | Core rate statistics with averaging               |
| `Rate`                | Individual rate measurement with averaging period |
| `Frequency`           | Event frequency tracking                          |
| `RateAverages`        | Computes averaged rate values                     |
| `RateSummaryListener` | Emits periodic rate summaries                     |
| `PersistenceHelper`   | Persists statistics across restarts               |

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
