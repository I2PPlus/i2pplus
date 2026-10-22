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

## Building

```bash
# Gradle
./gradlew :apps:jrobin:jar

# Ant (from repo root)
ant buildJrobin
```
