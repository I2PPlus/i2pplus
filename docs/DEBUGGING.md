# Debugging I2P+

This document covers open-source tools and techniques for debugging the I2P+ router at runtime.

---

## Table of Contents

- [JDWP (Remote Debugging)](#jdwp-remote-debugging)
- [VisualVM](#visualvm)
- [jhat / OQL](#jhat--oql)
- [jconsole](#jconsole)
- [jmap / jstack / jinfo](#jmap--jstack--jinfo)
- [jcmd](#jcmd)
- [Async Profiler](#async-profiler)
- [BTrace](#btrace)
- [curl-based HTTP introspection](#curl-based-http-introspection)
- [GC Logging](#gc-logging)
- [JMX Export](#jmx-export)

---

## JDWP (Remote Debugging)

The router wrapper can expose a JDWP port for remote debugging. Add to `wrapper.config`:

```
wrapper.java.additional.N=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

**Note:** The `address=*:5005` syntax binds to all interfaces (JDK 9+). On JDK 8, use `address=5005` (localhost only) or `address=0.0.0.0:5005` (all interfaces).

### Connecting

```bash
jdb -attach localhost:5005
```

### Breakpoints vs Logpoints

- **Never set blocking breakpoints** in production — the wrapper watchdog kills the router if any thread is suspended >10s.
- Use **logpoints** (non-stopping, expression-evaluated) instead.
- Safe breakpoints using `jdwp_resume_until_event` suspend briefly, inspect, then auto-resume.

### Common jdb commands

```
> classes                                           # list loaded classes matching pattern
> methods net.i2p.router.transport.TransportImpl    # list methods
> set breakpoint net.i2p.router.tunnel.HopProcessor:142  # blocking — dangerous
> set trace methods true                            # trace (noisy but non-stopping)
> threads                                           # list all threads
> thread main                                       # switch to thread
> where                                             # print stack
> locals                                            # print locals
> dump this                                         # dump fields
```

---

## VisualVM

**Source:** https://visualvm.github.io/ (OpenJDK project, GPL-2.0)

All-in-one profiler, heap dumper, thread analyzer, and sampler.

### Setup

```bash
# Install (Ubuntu/Debian)
apt install visualvm

# Or download standalone from https://visualvm.github.io/download.html
```

### Attaching to a remote router

1. Start the router with JMX enabled (see [JMX Export](#jmx-export) below).
2. In VisualVM: File → Add JMX Connection → `localhost:7090`
3. Optionally check "Use security credentials" (match `jmxremote.password`).

If connecting locally, VisualVM auto-discovers the router JVM.

### Common uses

| Tab           | Purpose                                                      |
| ------------- | ------------------------------------------------------------ |
| **Monitor**   | Heap, metaspace, CPU, classes, threads                       |
| **Threads**   | Inspect thread dumps, find deadlocked threads                |
| **Sampler**   | CPU / memory sampling (low overhead)                         |
| **Profiler**  | Hot methods in the router's own code only (exclude java.*)   |
| **Heap Dump** | Capture live heap for analysis, inspect by class or instance |
| **JConsole**  | Tab replacement for jconsole                                 |

### Profiling tip

Filter to package `net.i2p` only:

```
Profiler > CPU Settings > Package filter: Include: net.i2p
```

This avoids spending sampling time inside JDK methods.

---

## jHat / OQL Analysis

Built into the JDK (`jhat` removed in JDK 9+). Use the modern replacement:

### Eclipse MAT (Memory Analyzer Tool)

**Source:** https://eclipse.dev/mat/ (EPL-1.0, free)

```bash
# Install
apt install eclipse-mat

# Analyze a heap dump
mat /tmp/heapdump.hprof
```

### Generating a heap dump

```bash
jmap -dump:live,format=b,file=/tmp/heapdump.hprof <pid>
```

Or from the router console: `http://localhost:7657/stats.jsp?action=gc&dumpHeap=true`

### OQL Queries (MAT or jhat)

```
# All instances of a class
SELECT * FROM net.i2p.data.Hash

# Top 20 largest objects
SELECT * FROM java.lang.Object ORDER BY retainedHeapSize DESC LIMIT 20

# Find what holds a reference to a specific object
SELECT * FROM net.i2p.router.tunnel.pool.TunnelPool

# Strings by length (detect leaks)
SELECT s.toString() FROM java.lang.String s ORDER BY s.value.length DESC
```

### jhat (JDK 8 only, replaced by OQL in MAT)

```bash
jhat /tmp/heapdump.hprof
# Opens browser at http://localhost:7000
```

---

## jconsole

Bundled with JDK. Lightweight JMX browser.

```bash
jconsole localhost:7090
```

Useful for:
- Watching heap usage over time
- Checking thread count
- Invoking `MBeans` — the router exposes several under `net.i2p.router`

---

## jmap / jstack / jinfo

Built into the JDK (available via `jdk-misc` or `jdk-utils` packages).

### jstack — Thread dumps

```bash
# Full thread dump
jstack -l <pid>

# Thread dump with locks info
jstack -l <pid> > /tmp/threaddump.txt

# Repeat for 3 dumps (catches deadlocked or spinning threads)
for i in 1 2 3; do jstack -l <pid> > /tmp/jstack.$i; sleep 5; done
```

### jmap — Heap info and dumps

```bash
# Heap summary
jmap -heap <pid>

# Histogram of live objects
jmap -histo:live <pid>

# Class histogram sorted by instance count
jmap -histo <pid> | head -40

# Full heap dump for MAT / jhat
jmap -dump:live,format=b,file=/tmp/dump.hprof <pid>
```

### jinfo — JVM flags

```bash
# All flags
jinfo -flags <pid>

# Read a specific flag
jinfo -flag MaxHeapSize <pid>

# Enable a flag at runtime (if supported)
jinfo -flag +PrintGCDetails <pid>
```

---

## jcmd

Unified diagnostic command since JDK 8. Subsumes jmap, jstack, jinfo.

```bash
# List all commands
jcmd <pid> help

# Thread dump
jcmd <pid> Thread.print

# Heap dump
jcmd <pid> GC.heap_dump /tmp/heap.hprof

# GC report
jcmd <pid> GC.class_histogram

# JVM version and properties
jcmd <pid> VM.version
jcmd <pid> VM.system_properties

# Native memory tracking (requires -XX:NativeMemoryTracking=summary at startup)
jcmd <pid> VM.native_memory summary
```

---

## Async Profiler

**Source:** https://github.com/async-profiler/async-profiler (Apache-2.0)

Low-overhead CPU and allocation profiler using `perf_event_open` + `ebpf`.

```bash
# Install
git clone https://github.com/async-profiler/async-profiler.git
cd async-profiler && make

# Attach to running router
./profiler.sh -d 60 -o flamegraph -i 500us <pid>
# Output: flamegraph-<pid>.svg — open in browser

# CPU + allocation flamegraph
./profiler.sh -d 30 -e cpu -e alloc -o combined <pid>
```

### Typical use for I2P

Profile a tunnel build storm or path selection:

```bash
./profiler.sh -d 120 -e cpu -o flamegraph \
  -f /tmp/tunnel-build.svg <pid>
```

Then examine the flamegraph for hot methods in `net.i2p.router.tunnel.pool.*`.

---

## BTrace

**Source:** https://github.com/btraceio/btrace (GPL-2.0 with classpath exception)

Dynamic tracing without JVM restart. Write small scripts in Java-like syntax.

```bash
# Attach to running router
btrace <pid> script.java
```

Example script — log all tunnel build attempts:

```java
import net.i2p.router.tunnel.pool.*;

@BTrace
public class TunnelTrace {
    @OnMethod(clazz="BuildExecutor", method="tryBuild")
    public static void onBuild(String dest) {
        println("Tunnel build: " + dest);
    }
}
```

---

## JFR + JDK Mission Control

**Requires:** OpenJDK 11+ (Oracle JDK, Adoptium, or any build with JFR).

Low-overhead flight recording suitable for production. Captures method samples, heap allocations, GC pauses, thread sleeps, lock contention, and I/O events.

```bash
# Start a 60-second recording on the running router
jcmd <pid> JFR.start duration=60s filename=/tmp/recording.jfr

# Or add to wrapper.config for continuous recording:
wrapper.config.additional.N=-XX:StartFlightRecording=delay=5m,duration=1h,filename=/tmp/record.jfr,maxsize=500M
```

### Analysis

Open the `.jfr` file in **JDK Mission Control** (JMC):

```bash
apt install openjdk-17-jdk  # ships with JMC
jmc /tmp/recording.jfr
```

JMC tabs useful for I2P debugging:

| Tab                 | Purpose                                                  |
| ------------------- | -------------------------------------------------------- |
| **Flight Recorder** | Method profiling (hot methods by stack depth)            |
| **Memory**          | Allocation profiling, GC pressure, object statistics     |
| **Threads**         | Lock contention, thread stalls, blocked threads          |
| **I/O**             | Socket reads/writes, file I/O (if enabled)               |
| **Exceptions**      | Captures all thrown exceptions with stack traces         |
| **Event Browser**   | Browse every recorded event by type                      |

**Note:** `-XX:+UnlockCommercialFeatures` is no longer needed — JFR is free and built in since OpenJDK 11.

---

## Arthas

**Source:** https://github.com/alibaba/arthas (Apache-2.0)

Real-time diagnostic tool without scripts or restart. Attaches via agent and exposes an interactive shell (telnet or web).

```bash
curl -O https://arthas.aliyun.com/arthas-boot.jar
java -jar arthas-boot.jar <pid>
```

### Useful commands for I2P

```
# Watch method return values live
watch net.i2p.router.tunnel.pool.BuildExecutor tryBuild '{params,returnObj}' -x 2

# Trace method execution time (ms)
trace net.i2p.router.tunnel.HopProcessor processMessage '#cost > 100'

# Monitor method throughput and latency
monitor net.i2p.router.transport.TransportImpl afterSend

# Inspect router state
vmtool --action getInstances --className net.i2p.router.Router --express 'instances[0].getTunnelManager()'

# List top threads by CPU
thread -n 5
```

Arthas is more ergonomic than BTrace for ad-hoc inspection — no compilation step, no separate script file, and real-time output.

**Note:** On JDK 27+, Arthas may need `--attach-agent` flags due to tightened attach API permissions. Check the [Arthas troubleshooting guide](https://arthas.aliyun.com/en/doc/troubleshooting.html) if attachment fails.

---

## IDE Remote Debugging

Attach your IDE to the router's JDWP port for step-through debugging.

- **IntelliJ:** Run → Edit Configurations → Add New → Remote JVM Debug. Set host `localhost`, port `5005`. No JVM arguments needed (already in `wrapper.config`). Click Debug.
- **Eclipse:** Run → Debug Configurations → Remote Java Application. Set host `localhost`, port `5005`, select the i2pplus project. Click Debug.
- **VS Code:** Install Extension Pack for Java, create `.vscode/launch.json` with `{"type":"java","request":"attach","name":"Attach to I2P+","hostName":"localhost","port":5005}`. Run → Start Debugging.

**Caveats:** Never set blocking breakpoints in production — the wrapper watchdog kills the router on any thread suspension >10s (see [JDWP section](#jdwp-remote-debugging)). Use logpoints (non-suspending breakpoints that log and continue) for production diagnostics.

---

## curl-based HTTP introspection

Lightweight — no external tools needed beyond `curl`. The router console exposes:

```bash
# Current bandwidth rate
curl -s 'http://127.0.0.1:7657/stats.jsp' | sed 's/<[^>]*>//g' | grep "bandwidth"

# Tunnel counts
curl -s 'http://127.0.0.1:7657/stats.jsp' | sed 's/<[^>]*>//g' | grep "Tunnel"

# NetDB stats
curl -s 'http://127.0.0.1:7657/netdb.jsp'

# Heap / memory
curl -s 'http://127.0.0.1:7657/configstats?stat=jvm.memory.usage'

# Router info as JSON (I2PControl API)
curl -s 'http://127.0.0.1:7650/i2pcontrol/' \
  -d '{"jsonrpc":"2.0","id":"1","method":"RouterInfo"}'
```

### Watchdog-safe sampling

Prefer curl over breakpoints for reading stat values. The 10s wrapper watchdog makes long thread suspension unsafe.

---

## GC Logging

Add to `wrapper.config` (the `.N` suffix is the line number — yours may differ):

```
wrapper.config.additional.1=-Xlog:gc*:file=/tmp/gc-%t.log:tags,time,uptime,level
```

**Note:** JDK 9+ only supports the unified `-Xlog:gc*` syntax. The old JDK 8 flags (`-XX:+PrintGCDetails`, `-XX:+PrintGCTimeStamps`, `-XX:+PrintGCDateStamps`, `-Xloggc:`) were removed and will be ignored or cause a fatal error on JDK 9+.

### Analysis tools

```bash
# GCViewer (open source)
# https://github.com/chewiebug/GCViewer
java -jar gcviewer.jar /tmp/gc.log

# Or direct grep
grep "Full GC" /tmp/gc.log | tail -20
grep "Pause" /tmp/gc.log | cut -d' ' -f6 | sort -n | tail -5
```

---

## JMX Export

Enable JMX in `wrapper.config` (the `.N` suffix is the line number — yours may differ):

```
wrapper.config.additional.N=-Dcom.sun.management.jmxremote
wrapper.config.additional.N=-Dcom.sun.management.jmxremote.port=7090
wrapper.config.additional.N=-Dcom.sun.management.jmxremote.authenticate=false
wrapper.config.additional.N=-Dcom.sun.management.jmxremote.ssl=false
```

Then connect with VisualVM, jconsole, or any JMX client.

---

## Quick Reference

| Symptom         | Tool                | Command                                                           |
| --------------- | ------------------- | ----------------------------------------------------------------- |
| High CPU        | Async Profiler      | `profiler.sh -d 60 <pid>`                                         |
| Memory leak     | jmap + MAT          | `jmap -dump:live,format=b,file=dump.hprof <pid>`                  |
| Thread stuck    | jstack ×3           | `for i in 1 2 3; do jstack <pid> > /tmp/jstack.$i; sleep 5; done` |
| GC pressure     | GC logs             | grep `Full GC` in gc.log                                          |
| Hot methods     | VisualVM sampler    | Sampler tab, filter: `net.i2p`                                    |
| Router stats    | curl                | `curl -s 'http://127.0.0.1:7657/stats.jsp'`                       |
| Bytecode check  | javap               | `javap -p -c net.i2p.router.Router`                               |
| Network capture | tcpdump / wireshark | `tcpdump -i any port 8887`                                        |