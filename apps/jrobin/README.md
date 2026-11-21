# JRobin (`jrobin/`)

Fork of the RRD4J library (Round-Robin Database for Java) for efficient time-series data storage and graphing. Used for router statistics in the console.

## What is Round-Robin Database?

RRD uses fixed-size databases that store data at regular intervals. Older data is automatically consolidated or discarded as new data arrives, ensuring:
- **Constant storage** - No growth over time
- **Efficient queries** - Pre-aggregated data at multiple resolutions
- **Graph-ready** - Built-in graphing from consolidated data

## How I2P Uses It

Router statistics (bandwidth, peer count, tunnel usage) are logged at regular
intervals. The console then graphs these. Each stat period gets its own RRD
file with a single archive (`GraphListener.java`):

```
Stat period    Rows                    Retention
-----------------------------------------------------
  1 min        131040 (the cap)        91 days
  5 min        26208                   91 days
  1 hour       2184                    91 days
  1 day        1440 (the floor)        1440 steps
```

Rows are `clamp(91 days / period, 1440, 131040)`, so anything sampled hourly or
more often is kept for 91 days, and a longer period keeps at least 1440 rows.
Each archive uses the `AVERAGE` consolidation function, XFF 0.9, and one step
per row.

### Where the archives live

Persistence is on by default (`routerconsole.graphPersistent`). Archives are
written to `<router dir>/rrd/` as `rrd-<stat name>.<period>.jrb`, one file per
stat and period. The `.jrb` container is jrobin's own: it is not interchangeable
with `.rrd` files from RRD4J or other RRD implementations. With
`routerconsole.graphPersistent=false` the archives use the memory backend
instead — nothing is written to disk, and the history is gone after a restart.

How much of the archive the console plots is a separate setting,
`routerconsole.graphPeriods` (default 60 periods, so one hour for a stat
sampled every minute).

## Data Consolidation

When moving between resolutions, RRD applies functions:
- `AVERAGE` - Mean of values
- `MAX` / `MIN` - Peak values
- `LAST` - Most recent value

The router only creates `AVERAGE` archives; the other functions are available
from the library.

## Key Classes

| Class          | Purpose                       |
| -------------- | ----------------------------- |
| `RrdDb`        | Database, holds datasources   |
| `RrdDef`       | Database definition           |
| `Sample`       | Insert values                 |
| `RrdGraph`     | Render to image               |
| `RrdGraphDef`  | Graph definition              |

## Packages

- `org.rrd4j.core` - Database engine
- `org.rrd4j.graph` - Graph rendering
- `org.rrd4j.data` - Data manipulation and consolidation

## Divergence from upstream I2P's copy

This module is also a fork of the `apps/jrobin` carried by upstream I2P, and it
has been rewritten rather than patched: 207 files differ, 24,507 lines added and
8,477 removed.

- **SVG backend.** Upstream's hand-written trio `net.i2p.rrd4j.SimpleSVGMaker`,
  `SimpleSVGGraphics2D` and `SimpleSVGImageWorker` is gone, replaced by a
  vendored copy of JFreeSVG (`org.jfree.svg`, 21 files, 6,129 lines) driven
  through a new `org.rrd4j.graph.SVGImageWorker`.
- **Graph package.** Added: `MonotoneSpline` (curve smoothing), `RrdGraphMeta`
  (plot geometry and series as JSON, for the console's hover readout), and
  `eu.bengreen.data.utility` — a largest-triangle-three-buckets downsampler
  alongside the existing `DownSampler`. `RrdGraphDef`, `RrdGraphGenerator`,
  `TimeAxis` and `ValueAxis` are largely rewritten.
- **Core package.** Also substantially changed: 74 files, 6,092 lines added and
  4,457 removed, including the `timespec` parser and the `jrrd` file reader.
- **Tests.** Upstream has none for this module; this copy has 26 test files
  under `java/test/junit/org/rrd4j/`.

What has not changed is what the console depends on: the archive layout, the
`.jrb` container, and the retention constants in
`net.i2p.router.web.GraphListener` match upstream's `SummaryListener` line for
line, so a graph holds the same span of history in either tree.

## Building

```bash
# Gradle
./gradlew :apps:jrobin:jar

# Ant (from repo root)
ant buildJrobin
```
