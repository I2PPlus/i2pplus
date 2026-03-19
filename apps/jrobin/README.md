# JRobin (`jrobin/`)

Fork of the RRD4J library (Round-Robin Database for Java) used for high-performance time-series data logging and graphing, primarily for router statistics.

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
