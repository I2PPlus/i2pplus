# Coding I2P+

This document collects pro-quality Java coding practices for the I2P+ codebase
and the project's binding rules — commit policy, testing conventions,
architecture/performance requirements, and the DataHelper canonical source
rule — in one self-contained guide. It stands alone; no other file is required
to understand how to write code for this repository.

---

## Table of Contents

- [Readability & Style](#readability--style)
- [Naming](#naming)
- [Effective Java Core Rules](#effective-java-core-rules)
- [Modern Java](#modern-java)
- [Error Handling & Exceptions](#error-handling--exceptions)
- [Null Handling & Optional](#null-handling--optional)
- [Collections & Data Structures](#collections--data-structures)
- [Streams & Functional Style](#streams--functional-style)
- [Object Creation & Lifecycle](#object-creation--lifecycle)
- [Memory Leak Prevention](#memory-leak-prevention)
- [Concurrency](#concurrency)
- [Deadlock Avoidance](#deadlock-avoidance)
- [Efficiency & Performance](#efficiency--performance)
- [The Space/Speed Tradeoff Myth](#the-spacespeed-tradeoff-myth)
- [Project Anti-Patterns](#project-anti-patterns)
- [Bug Prevention Patterns](#bug-prevention-patterns)
- [Tunnel Subsystem Rules](#tunnel-subsystem-rules)
- [DataHelper Canonical Source Rule](#datahelper-canonical-source-rule)
- [Code Quality Standards](#code-quality-standards)
- [Testing](#testing)
- [File Organization](#file-organization)
- [Logging](#logging)
- [Security](#security)
- [Commits](#commits)
- [Build & Test Commands](#build--test-commands)
- [Change Review Workflow](#change-review-workflow)
- [AI Agent Working Rules](#ai-agent-working-rules)
- [Design Patterns & SOLID](#design-patterns--solid)
- [Documented Architectural Decisions](#documented-architectural-decisions)
- [Sources](#sources)

---

## Readability & Style

The project follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
as the default convention **with the following explicit deviations**:

| Rule                | Google Style                               | This Repo                                  | Rationale                                                 |
| ------------------- | ------------------------------------------ | ------------------------------------------ | --------------------------------------------------------- |
| Indentation         | 2 spaces                                   | 4 spaces                                   | Historical convention; changing would cause massive diffs |
| Column limit        | 100                                        | 100                                        | ✅ Matches                                                |
| Braces              | K&R                                        | K&R                                        | ✅ Matches                                                |
| `@Override`         | Required                                   | Required                                   | ✅ Matches                                                |
| `switch`            | Exhaustive enums                           | Exhaustive enums                           | ✅ Matches                                                |
| Finalizers          | Never                                      | Never                                      | ✅ Matches                                                |
| Imports             | No wildcard, no unused                     | No wildcard, no unused                     | ✅ Matches                                                |
| Vertical whitespace | 1 blank line                               | 1 blank line                               | ✅ Matches                                                |
| Magic numbers       | Named constants                            | Named constants                            | ✅ Matches                                                |
| Comments            | Explain *why*, Javadoc on public/protected | Explain *why*, Javadoc on public/protected | ✅ Matches                                                |

**When in doubt, follow the surrounding file's existing style.**

Formatter settings for IDE import can be derived from the repo's existing
files: 4-space indentation, no tabs, no trailing whitespace.

---

## Naming

From Effective Java Item 68 and the Google guide:

| Kind                         | Convention            | Example                                  |
| ---------------------------- | --------------------- | ---------------------------------------- |
| Types (class/interface/enum) | PascalCase, noun      | `TunnelPool`, `BuildRequestor`           |
| Methods                      | camelCase, verb       | `selectPeers()`, `countsAsPoolFailure()` |
| Fields                       | camelCase             | `_ghostUntil`, `buildRequestor`          |
| Constants (`static final`)   | UPPER_SNAKE_CASE      | `MIN_BUILD_SPACING_MS`                   |
| Parameters                   | camelCase             | `buildSuccess`                           |
| Local variables              | camelCase, meaningful | `poolSize` (not `ps`)                    |
| Acronyms                     | Capitalize like words | `HtmlBuilder` (not `HTMLBuilder`)        |

The tunnel subsystem uses a leading underscore (`_fieldName`) for internal
state fields — keep this convention in `net.i2p.router.tunnel.*` files.

---

## Effective Java Core Rules

Condensed from the 90 items of *Effective Java*, 3rd edition. These are the
items that most frequently surface in code review:

### Object Creation

| Item | Rule                                                                                             |
| ---- | ------------------------------------------------------------------------------------------------ |
| 1    | Prefer **static factory methods** over constructors (named, can cache)                           |
| 2    | Use the **builder pattern** when a constructor has many parameters                               |
| 4    | Enforce non-instantiability of utility classes with a **private constructor**                    |
| 6    | **Avoid creating unnecessary objects** — reuse, don't rebuild                                    |
| 7    | **Eliminate obsolete object references** — null out stale fields, don't hold collections forever |
| 9    | Use **try-with-resources** for anything `AutoCloseable`                                          |

### equals / hashCode / clone

| Item  | Rule                                                                                                   |
| ----- | ------------------------------------------------------------------------------------------------------ |
| 10/11 | Override `equals` and `hashCode` **together**, honoring the contract; never use `==` for value objects |
| 13    | **Prefer copy constructors/factories over `clone()`** — `Cloneable` is broken by design                |
| 14    | Implement `Comparable` for natural ordering; keep `compareTo` consistent with `equals`                 |

### Classes & Interfaces

| Item | Rule                                                                                |
| ---- | ----------------------------------------------------------------------------------- |
| 15   | **Minimize mutability** — final fields, no setters where possible, defensive copies |
| 17   | Design for inheritance or **prohibit it** (final class or non-public constructor)   |
| 22   | Prefer interfaces to abstract classes for reusable types                            |
| 34   | **Prefer enums to int constants**                                                   |
| 36   | Use `EnumSet`/`EnumMap` instead of bit fields / `int` flags                         |
| 42   | Prefer lambdas to anonymous classes for single-method interfaces                    |
| 50   | Make **defensive copies** of mutable params before storing                          |
| 64   | Refer to objects by their **interfaces** (`List`, `Map`), not implementation types  |

### Methods

| Item | Rule                                                                              |
| ---- | --------------------------------------------------------------------------------- |
| 49   | **Check parameters** — validate at the boundary, throw `IllegalArgumentException` |
| 52   | **Overload carefully** — never with the same arity for ambiguous types            |
| 54   | **Return empty collections, never `null`** — see the null-safety contract below   |
| 58   | Prefer **for-each** over indexed loops                                            |
| 61   | **Avoid boxed primitives** in hot paths and comparisons (see Performance)         |
| 63   | **Never concatenate strings in a loop** — use `StringBuilder`                     |

---

## Modern Java

**The router builds with a Java 8 toolchain** (`-source 1.8 -target 1.8` in
`build.xml` and `build.gradle`). **Do not use language features introduced
after Java 8** — they will not compile.

| Feature                               | Introduced                  | Status           |
| ------------------------------------- | --------------------------- | ---------------- |
| `var` (local variable type inference) | Java 10                     | ❌ Not available |
| Text blocks (`"""..."""`)             | Java 13 (preview), 15 (std) | ❌ Not available |
| Records                               | Java 14 (preview), 16 (std) | ❌ Not available |
| Pattern matching `instanceof`         | Java 14 (preview), 16 (std) | ❌ Not available |
| Pattern matching `switch`             | Java 17 (preview), 21 (std) | ❌ Not available |
| Sealed classes/interfaces             | Java 17 (std)               | ❌ Not available |
| `switch` expressions (`yield`)        | Java 12 (preview), 13 (std) | ❌ Not available |

**Use traditional Java 8 patterns instead:**

- **Immutable data carriers**: `final` fields, constructor, getters, `equals`/`hashCode`
- **Type dispatch**: `if (obj instanceof Foo) { Foo f = (Foo) obj; ... }`
- **Multi-line strings**: `StringBuilder` or string concatenation
- **Local type inference**: explicit types (`String s = ...`)
- **Multi-way dispatch**: `if-else` chains or `enum` with `switch`

Keep the compatibility floor in mind: features used in shared modules must
compile with the minimum JDK the router supports (`build.xml`/`build.gradle`
target Java 8). **Verify with `ant clean jar` before committing** —
compilation will fail on Java 9+ syntax.

---

## Error Handling & Exceptions

From Effective Java Items 69-77 and common review findings:

| Rule                                            | Explanation                                                                                                            |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Never **swallow** exceptions                    | Empty `catch {}` hides failures. At minimum log, at best rethrow a contextual exception                                |
| Use exceptions only for exceptional conditions  | Not for control flow — an exception thrown per candidate in a selection loop is a performance bug (see Performance)    |
| Declare the **most specific** checked exception | `throws IOException`, not `throws Exception`                                                                           |
| Catch specific before general                   | `catch (FileNotFoundException)` before `catch (IOException)`                                                           |
| **Log XOR rethrow**                             | Logging and rethrowing duplicates stack traces; pick one per layer                                                     |
| Preserve the cause                              | Always chain: `throw new TunnelBuildFailedException("...", cause)`                                                     |
| Fail fast, fail loud                            | Validate inputs at the boundary (Item 49) — a `NullPointerException` deep inside a call chain is a debugging nightmare |
| Never swallow `InterruptedException`            | Restore the interrupt flag: `Thread.currentThread().interrupt()`                                                       |

A reasonable pattern for the router's long-running loops:

```java
try {
    processMessage(msg);
} catch (IOException ioe) {
    // log and continue — one bad message must not kill the pool
    if (_log.shouldLog(Log.WARN))
        _log.warn("Failed to process message from " + src, ioe);
}
```

---

## Null Handling & Optional

- **Never return `null` collections or arrays** — return `Collections.emptyList()`.
- **Null-safety contract for peer selection** (binding rule): all
  `selectPeers()` methods return `Collections.emptyList()` — never `null`.
  The Javadoc must state: *"Never null; an empty list means no peers could be
  selected."* Contract tests verify non-null return for all code paths.
- **Never pass `null` where an empty collection communicates intent.**
- **`Optional` is for return values, not parameters, not fields.**
- Avoid the `isPresent()` + `get()` dance — that is the Optional anti-pattern:

```java
// bad: ceremony, no benefit over a null check
if (profile.getLeaseSet().isPresent()) {
    return profile.getLeaseSet().get().getGateway();
}

// good: declarative
return profile.getLeaseSet().map(LeaseSet::getGateway).orElse(null);
```

- **`orElse` vs `orElseGet`**: `orElse(x)` evaluates `x` *eagerly* even when the
  Optional is present. If the fallback is expensive or has side effects, use
  `orElseGet(() -> ...)`. `orElse` is only for cheap constants.
- Prefer `map`/`flatMap`/`filter` chains; a chain with three `ifPresent` +
  `isPresent` calls is a smell.

### NPE Prevention

This codebase is Java 8: NPE messages carry only a line number (the "helpful"
messages naming the null variable arrived in JDK 14+). A crash deep inside a
call chain is therefore nearly impossible to trace — null bugs must be stopped
at the boundary, not diagnosed from the middle:

| Rule                                   | Practice                                                                                                                                                                                            |
| -------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Fail fast at entry points**          | `Objects.requireNonNull(param, "msg")` as the first statement of public methods/constructors — combined with `final` fields it makes the rest of the class null-free by construction                |
| **Never catch NPE as control flow**    | An NPE is a programming defect, not a recoverable condition; catching it hides the bug. Only exception: wrapping a third-party boundary you do not control — and even then prefer an explicit check |
| **Constant-first equality**            | `"ADMIN".equals(role)` never throws; `role.equals("ADMIN")` does when `role` is null                                                                                                                |
| **Null-safe lookups**                  | `map.getOrDefault(key, default)` and `Objects.toString(value, "")` — never dereference a lookup result without a check                                                                              |
| **Never initialize variables to null** | Initialize at declaration; prefer `final` fields — a null field is an unstated contract                                                                                                             |
| **Handle null at its source**          | Fix the producer (return `Optional`/empty collection/Null Object), never pass a possibly-null value up the chain for someone else to trip over                                                      |
| **Null Object pattern**                | Where absence is a valid state, a no-op implementation (e.g. a `NullLeaseSet`) removes the null branch entirely                                                                                     |
| **Annotations**                        | `@Nullable`/`@NonNull` document intent and feed static analyzers (SpotBugs, Error Prone, IDE inspections) — documentation only until a tool enforces them                                           |

Guava's null rules apply directly: in real codebases ~95% of collections are
not supposed to contain null. `Map.get()` returning null is ambiguous (absent
vs. null value) — design it away by keeping null values out of maps (sentinel
or a separate key set). `null` in application code is "cheap but ambiguous";
in this router it is a crash waiting for a specific message pattern.

---

## Collections & Data Structures

From the Oracle Collections tutorial and Effective Java Items 57-66:

- **Pick the right general-purpose implementation**: `HashSet`, `ArrayList`,
  `HashMap`, `LinkedHashMap` (insertion-ordered) cover most needs. Choose a
  `TreeSet`/`TreeMap` only when you genuinely need sorted iteration.
- **Refer by interface** — declare `List<String>`, never `ArrayList<String>`
  (Item 64). Exceptions: `EnumMap`, `ConcurrentHashMap` special methods.
- **Know your complexity**: `ArrayList.get()` is O(1), `LinkedList.get(i)` is
  O(n). If you index into a `List` by position in a loop, it must be an `ArrayList`.
- **`HashMap` iteration order is not deterministic** — never rely on it for
  logging, batching, or ordering-sensitive output.
- **FIFO trimming** (binding rule): when you need to evict oldest entries while
  preserving duplicate detection for late replies, use a
  `LinkedHashSet`/`LinkedHashMap`/`Deque` (the tunnel subsystem trims recent
  build IDs this way).
- **Read-time filtering over map sweeping** (binding rule): never `removeIf()`
  on a shared `ConcurrentHashMap` during hot-path selection. Instead, add
  fresh entries to an exclusion set at read time (value > cutoff), and prune
  oversized maps in a separate maintenance pass (e.g. `prunePeerMaps()`).
- **`Collections.unmodifiable*` when exposing state** — but only after the
  defensive copy (Item 50); unmodifiable wrappers on shared mutable maps still
  leak mutations to concurrent writers.
- **Primitive collections in hot paths** — consider **Eclipse Collections**
  or **FastUtil** for primitive-specialized collections (`IntList`, `IntIntMap`,
  etc.) to avoid autoboxing overhead — but new third-party dependencies require
  architecture review in this codebase; the zero-dependency alternative is
  primitive arrays / parallel arrays. The JDK 8 `IntStream`/`LongStream`/
  `DoubleStream` are available for stream-based primitive processing.

---

## Streams & Functional Style

Streams are welcome for declarative pipelines, but with constraints:

| Rule                                                                | Reason                                                                                                                                               |
| ------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| **No side effects in behavioral parameters**                        | `map`/`filter`/`forEach` lambdas must be stateless — the Stream spec says side effects are "generally discouraged" and their ordering is unspecified |
| **`peek` is for debugging only**                                    | Never rely on `peek` for production behavior — remove before commit                                                                                  |
| **Avoid streams inside loops over large collections**               | Stream setup cost per iteration turns O(n) into O(n·k) (see Performance)                                                                             |
| **Don't use streams for control flow**                              | `stream().findFirst().orElseThrow()` is fine; a stream that throws per-element to filter is not                                                      |
| **`findAny` is nondeterministic**                                   | Use `findFirst` when order matters (both are short-circuiting)                                                                                       |
| **Prefer `collect(toList())` over `forEach` + mutable accumulator** | Declarative, parallel-friendly                                                                                                                       |
| **Watch boxing**                                                    | `IntStream`/`LongStream`/`DoubleStream` avoid autoboxing; `Stream<Integer>` does not                                                                 |

---

## Object Creation & Lifecycle

- **Reuse expensive objects** — `ObjectMapper`, `DateTimeFormatter`,
  `SimpleDateFormat`, `Gson`, `SecureRandom`, `MessageDigest` instances are
  expensive to build. Construct once per class/module, not once per call —
  rebuilding them per request is a measurable hotspot in 24/7 services
  (see Sources, *Java Is Fast*).
- **Prefer `StringBuilder` over `+` in any loop** — repeated concatenation is
  O(n²). Reserve `+` for single-statement concatenations.
- **Avoid needless allocation in hot paths** — boxing, `String.format`, and
  per-iteration object creation are measurable in a 24/7 router.
- **Static fields of mutable types are a shared-state hazard** — a `static`
  `Map` mutated from multiple pool threads requires synchronization or
  replacement with a concurrent collection (see Concurrency).
- **Use `StringBuilder` pre-sized** when the target length is knowable
  (`new StringBuilder(2 * MAX_PEERS)`).

---

## Memory Leak Prevention

A 24/7 router with a 512 MB heap cannot survive a slow leak — the failure mode
is an `OutOfMemoryError` weeks after the introducing commit. Leaks are almost
always one of a small set of recognizable patterns:

| Pattern                                      | Why it leaks                                                                          | Prevention                                                                                                                                        |
| -------------------------------------------- | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Unbounded collections / caches**           | Entries are added forever, never evicted; the collection keeps every object reachable | Bound every cache — `net.i2p.util.LHMCache` (bounded LRU) exists for this; or prune in a separate maintenance pass (see read-time filtering rule) |
| **Static collections**                       | Static fields live as long as the JVM; everything they reference is never collectable | Avoid `static` collections for data; if unavoidable, bound them and provide a prune path                                                          |
| **Listeners / callbacks never deregistered** | The registry holds a reference to the subscriber forever                              | When registering with `net.i2p.util.EventDispatcher` (or any listener list), **always** deregister on removal/shutdown                            |
| **`ThreadLocal` without cleanup**            | Thread pools reuse threads; the value stays attached to the pooled thread forever     | Treat `ThreadLocal` as a resource: `remove()` in a `finally` block. `set(null)` does NOT clear the value                                          |
| **Non-static inner classes**                 | An inner/anonymous class holds an implicit reference to its outer instance            | Use `static` nested classes unless outer access is required                                                                                       |
| **Broken `equals`/`hashCode`**               | Hash collections can't recognize duplicates; every insert adds a distinct entry       | Always override both for objects used as keys (IDE-generated or `Objects.hash()`)                                                                 |
| **Unclosed resources**                       | Streams/sockets hold native memory plus heap                                          | try-with-resources (Java 8 supports it); legacy code: `finally { close(); }`                                                                      |
| **`String.intern()`**                        | Interned strings live in the string table, effectively uncollectable                  | Only intern values from a small, known set                                                                                                        |

Detection, before it crashes the router: heap usage climbing without bound
(JFR/VisualVM monitor), a rising object creation rate per message, and
`jcmd <pid> GC.class_histogram` for suspicious types — then Eclipse MAT
dominator trees to find the retaining roots (see `docs/DEBUGGING.md` →
heap-report.sh). Two metrics predict leaks early: gradual heap growth over
time, and a release that allocates 2× more objects for the same workload.

Buffer reuse: `net.i2p.util.ByteCache` / `SimpleByteCache` exist for `byte[]`
pooling — use them for hot-path buffer allocation instead of `new byte[n]`
per message.

---

## Concurrency

From Effective Java Items 78-84 and common JVM pitfalls:

| Rule                                               | Explanation                                                                                                                                                                                                                                                                                                               |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Prefer `java.util.concurrent` collections**      | `ConcurrentHashMap`, `CopyOnWriteArrayList` (read-heavy), `LinkedBlockingQueue` over manually synchronized collections                                                                                                                                                                                                    |
| **Synchronized collections ≠ compound safety**     | `Collections.synchronizedList` protects single ops only — check-then-act (`isEmpty()` then `get()`) is still racy                                                                                                                                                                                                         |
| **Minimize lock scope**                            | Never hold a lock while doing I/O, DNS lookups, or building tunnels — serialize only the critical section                                                                                                                                                                                                                 |
| **Prefer immutable state**                         | Final fields, no setters — immutable objects need no locking                                                                                                                                                                                                                                                              |
| **Volatile for visibility flags**                  | `volatile boolean _shutdown` for flags, not for compound state                                                                                                                                                                                                                                                            |
| **Atomic classes for counters**                    | `AtomicLong`/`AtomicInteger` for stats, sequence numbers                                                                                                                                                                                                                                                                  |
| **Never synchronize on interned/String objects**   | Lock on a dedicated private `final Object`                                                                                                                                                                                                                                                                                |
| **Threads: interrupt + join**                      | Shutdown must `interrupt()` then `join()` executor threads                                                                                                                                                                                                                                                                |
| **Don't block router threads on `Thread.sleep()`** | Use non-blocking results + executor spacing                                                                                                                                                                                                                                                                               |
| **Virtual threads (JDK 21+)**                      | Not available in this codebase (Java 8). If the project ever migrates, beware pinning when a virtual thread blocks inside a `synchronized` block — prefer `ReentrantLock` in code that may run on virtual threads. Note: `IntStream`/`LongStream`/`DoubleStream` ARE available in Java 8 for primitive stream operations. |

Thread-contention rules (binding): eliminate global locks that serialize
independent pool selections — no `synchronized` on shared/static maps that
guard all selections; each pool must select independently. The `BuildExecutor`
thread shutdown uses `interrupt()` + `join()`.

The router is a heavily multithreaded 24/7 process: global locks that serialize
independent pools are unacceptable.

---

## Deadlock Avoidance

A deadlock requires all four Coffman conditions (mutual exclusion, hold-and-wait,
no preemption, circular wait) to hold at once. In normal code the practical
culprit is **circular wait**: thread A holds lock1 and waits for lock2 while
thread B holds lock2 and waits for lock1. Breaking any single condition makes
deadlock structurally impossible on that code path:

| Rule                                              | Practice                                                                                                                                                                                                                      |
| ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Consistent lock ordering**                      | If multiple locks must be taken, every thread takes them in the same global order. For request-dependent pairs (e.g. two peers), order by `System.identityHashCode`, with a third lock as tie-breaker when the hashes collide |
| **Avoid nested locks**                            | A thread holding one lock at a time can never be part of a cycle — prefer single-lock designs or split the operation                                                                                                          |
| **Never call external code while holding a lock** | Callbacks, listeners, I/O and re-entrant calls under a lock are how order inversions sneak in                                                                                                                                 |
| **Timed lock attempts**                           | `ReentrantLock.tryLock(timeout, unit)` + release-and-backoff prevents indefinite blocking; add randomized retry delay to avoid livelock (threads retrying in lockstep)                                                        |
| **`wait()`/`notify()` discipline**                | Always `wait()` inside a loop (spurious wakeups), inside the `synchronized` block; prefer `notifyAll`; prefer `Condition`/`CountDownLatch` when a wait-for-signal is the point                                                |
| **Interrupt + join shutdown**                     | Shutdown paths `interrupt()` then `join()` executor threads — joining without interrupting first self-deadlocks on a blocked worker (BuildExecutor rule)                                                                      |
| **Never hold a lock during I/O**                  | Socket/file operations under a lock serialize the whole router and invite timeout-blame inversions                                                                                                                            |

Diagnosis: threads stuck `BLOCKED`/`WAITING` in a cycle. Take 3 thread dumps
~5 s apart (`jstack -l <pid>` or `jcmd <pid> Thread.print`) and look for
monitors held by one thread and waited on by another, forming a loop.

---

## Efficiency & Performance

Rules of thumb for a long-running network daemon:

1. **Measure before optimizing** — use JFR/JMC, Async Profiler, or `jcmd`
   (see `docs/DEBUGGING.md`). Optimizing code that is 0.1% of runtime is wasted
   effort; the profiler tells you where the 99.9% is.
2. **Fix algorithmic complexity first** — an O(n²) scan over 10,000 peers is a
   design bug, not a tuning problem. Choose the data structure that makes the
   operation you repeat most cheap.
3. **Avoid the eight classic anti-patterns** (from *Java Is Fast*, measured
   1198 ms → 239 ms for one realistic fix set):

| Anti-pattern                                    | Fix                                                                                      |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------- |
| String concatenation in a loop (O(n²))          | `StringBuilder`                                                                          |
| Stream inside a loop over the same data (O(n²)) | Hoist the stream, or use a plain loop                                                    |
| `String.format` in hot paths                    | Precompute or use concatenation/`StringBuilder`                                          |
| Autoboxing in hot paths                         | Primitive loops, `IntStream`/`LongStream`/`DoubleStream` (JDK 8+), primitive collections |
| Exceptions for control flow                     | Predicates, `Optional`, sentinel values                                                  |
| Too-broad `synchronized` blocks                 | Narrow the critical section                                                              |
| Rebuilding reusable objects per call            | Cache `ObjectMapper`, `DateTimeFormatter`, etc.                                          |
| Virtual thread pinning (JDK 21+)                | `ReentrantLock` instead of `synchronized`                                                |

4. **Cached statistics** (binding rule): never call expensive stats methods
   per-candidate. `getTunnelBuildSuccess()` performs 6 `RateStat` lookups —
   fetch once per selection/scan and pass the `double` down the call chain.
   Pattern: overload public methods with a cached variant; the old method
   delegates to it.
5. **State snapshotting** (binding rule): cooldowns/expiries are snapshotted at
   decision time (e.g. `_ghostUntil` set when the peer is marked), never
   recomputed per check — state changes must not extend/shorten active
   exclusions. Tests must verify the cooldown is respected despite network
   state changes mid-exclusion.
6. **GC pressure matters** — a 24/7 router with a 512 MB heap cannot tolerate
   per-message garbage. Reuse buffers, avoid boxing, prefer primitives.
7. **Know the memory model basics** — heap sizing (`-Xmx`/`-Xms`), Metaspace,
   and `CompressedClassSpaceSize` interact; a leak is usually a collection that
   grows without bound (see Sources, Oracle *Troubleshooting Memory Leaks*).

### Hot Path Optimization

On a Java 8 router with a small heap, the hot path is an allocation fight:
every object allocated on the hot path eventually costs a GC pause. The
discipline:

1. **Measure allocation rate, not just CPU** — async-profiler `alloc` mode or
   JFR shows which stacks allocate; the hot path should allocate near zero per
   message in steady state.
2. **Know the escape-analysis defeat patterns** — C2 can stack-allocate
   non-escaping objects, but gives up when: the object is passed to a
   non-inlined method (keep hot methods small — C2's default inlining budget
   is ~35 bytecode units), autoboxing occurs in a generic context
   (`Map<Integer, X>` key lookups), the object is stored into an array
   element, or it crosses a lambda/stream boundary. `Optional.of()` always
   allocates — in internal hot code return nullable directly.
3. **Primitive collections** — `Map<Integer, X>` in a hot loop autoboxes on
   every access. FastUtil/Eclipse Collections provide `Int2ObjectMap` etc.,
   but new third-party dependencies require architecture review in this
   codebase — the zero-dependency option is primitive arrays / parallel arrays.
4. **Precompile regexes** — `static final Pattern`, reuse `Matcher` via
   `reset()`; never `Pattern.compile()` or `String.matches()` per call.
5. **Right-size collections** — construct with expected capacity
   (`new HashMap<>(expectedSize)`) to avoid resize churn.
6. **Reuse mutable scratch** — one `StringBuilder` per call (cleared between
   uses) instead of allocating per concatenation.
7. **Guarded logging** — disabled log statements must allocate nothing: guard
   argument construction with `_log.shouldLog(Log.DEBUG)` (I2P `Log`) before
   building the message.
8. **Reuse buffers** — `ByteCache`/`SimpleByteCache` for hot-path `byte[]`
   (see Memory Leak Prevention).
9. **Don't fight the JIT** — a 24/7 router warms up naturally; keep hot
   methods small and monomorphic so C2 can inline and scalar-replace. Giant
   methods defeat inlining and escape analysis.

---

## The Space/Speed Tradeoff Myth

A common misconception — often visible in AI-generated code — is that writing
good code "almost always involves space/speed tradeoffs," so any efficient code
must buy speed with memory (or vice versa), and either choice is defensible.
In practice:

1. **Most inefficiencies are pure waste, not tradeoffs.** O(n²) string
   concatenation buys nothing; it is slower *and* allocates more. Rebuilding a
   `DateTimeFormatter` per call is slower *and* allocates more. Exceptions for
   control flow are slower *and* allocate more. The eight anti-patterns above
   are negative-sum — eliminating them improves both axes.
2. **The real tradeoffs are deliberate, documented, and measured.** When a
   genuine choice exists (e.g., a cache that trades memory for CPU, or a
   `CopyOnWriteArrayList` that trades write cost for lock-free reads), it must
   be: (a) a conscious decision by a human, (b) annotated in code or the
   decision log (see [Documented Architectural Decisions](#documented-architectural-decisions)),
   and (c) validated with a profiler or benchmark. Unexamined "tradeoffs" are
   usually just accidents.
3. **Correctness and clarity are never the thing being traded.** Space or time
   may yield to the other, but neither may yield to "it compiles" or "it
   passed one test."
4. **The default stance is: neither.** Write the straightforward correct code
   with the right data structure and no needless allocation. Optimize only
   what measurement says is hot, and only after the algorithmic fix is in.

For this codebase the rule is: **no performance change ships without a
measurement or a profiler trace**; no "tradeoff" ships without a documented
rationale (commit message or decision log entry).

---

## Project Anti-Patterns

These are patterns that were found and eliminated in the tunnel subsystem;
any reintroduction is a review blocker:

| Anti-Pattern                                                        | Fix                                                               |
| ------------------------------------------------------------------- | ----------------------------------------------------------------- |
| Per-peer `getTunnelBuildSuccess()`                                  | Cache once, pass as `double`                                      |
| `ConcurrentHashMap.removeIf()` in hot path                          | `addFreshCooldownExclusions()` at read time                       |
| `synchronized` on static map for all selections                     | Remove the lock after the map is eliminated                       |
| `null` returns from `selectPeers()`                                 | Return `Collections.emptyList()`                                  |
| Duplicate logic in `ClientPeerSelector` / `ExploratoryPeerSelector` | Extract to `TunnelPeerSelector`                                   |
| `Thread.sleep()` in executor thread                                 | Non-blocking result + executor spacing                            |
| All hops blamed on a timeout                                        | Only the contacted hop (via `BuildRequestor.getBuildRequestPeer`) |
| Counter-based keepalive (fires every 7.5 min)                       | Time-based (30 s actual)                                          |
| Embedded complex logic in 150+ line methods                         | Extract pure static helpers                                       |

---

## Bug Prevention Patterns

Binding rules derived from real bugs in the tunnel subsystem:

### Null-Safety Contracts
- **All** `selectPeers()` methods return `Collections.emptyList()` — never `null`.
- **Javadoc**: must state "Never null; an empty list means no peers could be selected".
- **Tests**: contract tests verify non-null return for all code paths.

### State Snapshotting
- **Cooldowns/expiries**: snapshot the threshold at mark time (e.g. `_ghostUntil`).
- **Never** recompute expiry on each check — state changes must not
  extend/shorten active exclusions.
- **Test**: verify the cooldown is respected despite network state change
  mid-exclusion.

### Asymmetric Comparator Bugs
- **Check**: comparator tiers must be transitive and consistently ordered.
- **Pattern**: Good > Low > Dead (not Good > Dead > Low).
- **Verification**: unit tests pinning cascade stage ordering.

---

## Tunnel Subsystem Rules

### Peer Selection
- **Build success**: fetch once per selection, pass `double buildSuccess` everywhere.
- **Cooldowns**: use `addFreshCooldownExclusions(map, cutoff, exclude)` — never sweep.
- **Quality comparator**: 6-stage cascade in `ClientPeerSelector`
  (excluded, acceptance, slow latency, activity, latency).
- **Exploratory pool**: must mirror client pool fixes (null safety, read-time
  filtering).

### Profile Management
- **Tiered expiry**: Active (send) > Passive (heard/heard-about) > Gossip (none).
- **Untracked profiles**: min of tier window + untracked window.
- **Proof of life**: 1-hour window for trusting stale RouterInfos.
- **Restore logic**: only if tier < 50% old size AND old size > 100.

### LeaseSet Management
- **Refresh throttle**: resolve force/defer before building LeaseSet.
- **Publish**: extract to `publishRefreshedLeaseSet(now)` for clarity.
- **Fallback**: `NO_TUNNELS` result excluded from pool failure backoff.

### Build Executor
- **Pass spacing**: 2 s minimum between build passes (`MIN_BUILD_SPACING_MS`).
- **Failure classification**: `countsAsPoolFailure()` excludes SUCCESS,
  DUP_ID, REJECT, NO_TUNNELS.
- **Timeout blame**: only the contacted hop is penalized (IBGW for inbound,
  OBEP for outbound).
- **Keepalive**: time-based (30 s), not counter-based.

---

## DataHelper Canonical Source Rule

**Rule**: `net.i2p.data.DataHelper` is the **single canonical source** for all
common data utilities:

- Hex/Base64/Base32 encoding and decoding (`toHexString`, `fromHexString`,
  `encode`, `decode`, `encodeBase32`, `decodeBase32`)
- Primitive I/O (`readLong`, `writeLong`, `readString`, `writeString`,
  `readDate`, `writeDate`)
- Properties serialization (`readProperties`, `writeProperties`, `loadProps`,
  `storeProps`)
- Date handling (`toDate`, `fromDate`, `toLong8`, `fromLong8`)
- Collection/array formatting (`toString` for Map, Collection, byte[],
  Properties)
- Comparison utilities (`eq` for Objects, Collections, byte[])

**Requirements**:

1. **Before adding new utility methods**, check DataHelper first.
2. **When refactoring**, replace duplicate implementations with DataHelper calls.
3. **Exceptions allowed** with an explicit comment, e.g.:

   ```java
   // DELIBERATE DEVIATION: DataHelper.loadProps() doesn't allow '#' in values
   // but torrent configs need '#' in filenames. See I2PSnarkUtil.loadProps()
   ```

4. **No new classes** for hex/Base64/Base32/primitive I/O without architecture review.

**Known deviations** (audit document: `wip/datahelper-duplication-audit.md`):

| ID     | Location                          | Pattern                             | DataHelper Equivalent                      | Status               |
| ------ | --------------------------------- | ----------------------------------- | ------------------------------------------ | -------------------- |
| DH-001 | `ConsolePasswordManager.java:106` | Custom `HexDecode()`                | `fromHexString()`                          | Fix recommended      |
| DH-002 | `I2PSnarkUtil.java:2129`          | Custom `loadProps`/`storeProps`     | `loadProps`/`storeProps`                   | Documented exception |
| DH-003 | 50+ files                         | `Integer.toHexString()` for logging | `toHexString(byte[])` / `toString(byte[])` | Low priority         |
| DH-004 | `EepGet.java:2760`                | Custom `lc8hex(int)`                | `toString(toLong(4, nc))`                  | Specialized, keep    |

Verification: `grep -r "HexDecode\|Character.digit.*16\|custom.*hex" --include="*.java" | grep -v DataHelper`

---

## Code Quality Standards

### Complexity Limits
- **Method cyclomatic complexity**: > 10 = extract; > 15 = mandatory decomposition.
- **Cognitive complexity**: > 20 = extract pure decision helpers.
- **File size**: > 1000 lines = God Class candidate for decomposition.

### God Class Decomposition
- **Target**: classes > 1000 lines or > 20 methods with high cyclomatic complexity.
- **Pattern**: extract pure decision logic into `static` package-visible methods.
- **Testability**: extracted methods must be testable without router context.
- **Documentation**: each extracted method gets full Javadoc with `@param`,
  `@return`, `@since`.

### Circular Dependency Elimination
- **Rule**: the `tunnel` ↔ `tunnel.pool` dependency cycle must be broken via
  base interfaces.
- **Technique**: move shared types (e.g. `TunnelCreatorConfig`) to the base
  package.
- **Verification**: circular-dependency analysis on `net.i2p.router.tunnel`.

### Code Duplication Prevention
- **Rule**: when two selectors share logic (> 30 lines), extract a common
  base class.
- **Example**: `ClientPeerSelector` / `ExploratoryPeerSelector` →
  `TunnelPeerSelector`.

### Documentation Requirements
- **All** public/protected/package-visible extracted methods:
  - Full Javadoc with `@param`, `@return`, `@since`
  - Explanation of *why* (not just *what*)
  - Reference to hot-path context where applicable
- **Private** complex methods: inline Javadoc explaining the algorithm.

---

## Testing

Project policy — add JUnit unit tests for new logic wherever it can be
exercised without a running router: pure decision functions, validators,
parsing, and file-generation helpers.

- **Extraction**: prefer extracting logic into static/package-visible methods
  so tests can reach it, rather than leaving it buried in private methods of
  context-heavy classes (e.g. servlets).
- **Run the tests**: `ant test` in the affected module (or the top-level
  build) after changes; tests must pass before committing.
- **Style**: mirror the existing test layout
  (e.g. `apps/i2psnark/java/test/junit/net/i2p/i2psnark/`).

Style guidance from the Mockito good-tests wiki:

| Rule                                          | Explanation                                                                                                                               |
| --------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| **Compact tests**                             | One behavior per test; minimal setup; a test over 40 lines is a candidate for splitting                                                   |
| **No tautological assertions**                | `assertEquals(actual, actual)` or asserting a mock's own return value verifies nothing                                                    |
| **Don't mock types you don't own**            | Mock `PeerProfile`, `RouterContext`, `TunnelInfo`; do not mock JDK collections or your own production classes where a real instance works |
| **Test the contract, not the implementation** | Null-safety: `selectPeers()` never returns `null`; comparators: cascade ordering Good > Low > Dead                                        |
| **Boundary conditions**                       | Exact thresholds, empty inputs, null inputs, cascade ordering                                                                             |
| **Mutation testing**                          | Consider PIT when a decision method is critical — if killing a mutant doesn't fail a test, the test is weak                               |

---

## File Organization

New test classes for extracted logic live next to the subsystem they test:

```
router/java/test/junit/net/i2p/router/tunnel/pool/
  *DecisionTest.java       # Pure decision helpers
  *CandidateTest.java      # Eligibility predicates
  *QualityTest.java        # Comparator cascades
  *RestoreTest.java        # Tier restore/eviction
  *BuildSuccessTest.java   # Build success gating
  *CooldownTest.java       # Cooldown behavior
  *EvictionTest.java       # Read-time filtering
  *ContractTest.java       # Null-safety contracts
  *PacingRulesTest.java    # Build spacing/failure rules
  *TimeoutBlameTest.java   # Single-hop blame
  *FifoTrimTest.java       # FIFO ID set
  *RegenerateTest.java     # Duplicate sequence
  *SelectPeersWarnTest.java # Rate-limited logging

router/java/test/junit/net/i2p/router/peermanager/
  *DecisionTest.java       # Reliability/proof-of-life
  *CandidateTest.java      # Eligibility/expiry
  *RestoreTest.java        # Tier restore/stale
  *BuildSuccessTest.java   # Build success ratio
```

---

## Logging

- **Never log secrets** — passwords, keys, tokens, hashes of either (CodeQL
  `java-sensitive-log` query flags this). The router console logs must not
  contain credentials even at DEBUG.
- **Include IP:PORT in network-operation logs** — but strip credentials from
  URLs before logging.
- **Level discipline**: `ERROR` = broken now; `WARN` = degraded but
  continuing; `INFO` = lifecycle milestones; `DEBUG` = diagnostics. Failure
  `WARN`s during peer discovery are rate-limited (3 min silence, then
  1 min/pool) to avoid log spam.
- **Log XOR rethrow** (see Error Handling).
- **Use SLF4J-style parameterized logging** where the codebase uses SLF4J
  (`log.debug("peer {} failed", peer)`) — avoids string building when the
  level is off. SLF4J 2.0 offers the fluent API as well.
- **Guard expensive argument construction** with `shouldLog(Log.DEBUG)` when
  the message requires allocation or serialization.

---

## Security

- **Never trust input**: validate sizes, types, and ranges at the boundary —
  a tunnel build request, a console form field, or a peer-supplied string can
  all be hostile.
- **Deserialization is RCE territory** (OWASP Insecure Deserialization): never
  deserialize data from untrusted peers; prefer explicit parsing of known
  formats over `ObjectInputStream`/`readObject` of wire data.
- **Defensive copies** (Item 50) for mutable data crossing trust boundaries.
- **Ban/log with IP:PORT**, never with user-identifying data.
- **No secrets in code, configs committed to the repo, or logs** — use
  environment variables or the router's config file with restricted perms.
- **`SecureRandom` for anything adversarial** (keys, nonces, session IDs) —
  not `Math.random()` or `Random`.

---

## Commits

- Never add a Signed-off-by (or any sign-off) footer to commit messages.
- One-line subject, optionally followed by a blank line and a short body.
- Verify commit content before pushing; stage only intended files.

---

## Build & Test Commands

```bash
ant test                    # Full test suite
ant test -Dtest=*Test       # Specific test pattern
ant clean jar               # Build router.jar
```

**Faster single-module test:**
```bash
cd router/java && ant test -Dtest=MySpecificTest
```

Router module (from `router/java`, using relative paths):

```bash
ant test -Dmockito.home=tools/test/mockito \
  -Dhamcrest.home=tools/test/hamcrest \
  -Djunit.home=tools/test/junit
```

Aggregate results from `/tmp/build-i2p/reports/router/junit/TEST-*.xml`:

```bash
grep -h '<testsuite ' TEST-*.xml | sed -E 's/^<testsuite errors="([0-9]+)" failures="([0-9]+)"[^>]*tests="([0-9]+)".*$/\3 \1 \2/' \
  | awk '{t+=$1; e+=$2; f+=$3} END {print "tests="t" failures="f" errors="e}'
```

---

## Change Review Workflow

1. `git log --oneline -20` — review recent commits.
2. `git show <sha> -p` — review each commit.
3. Run a static bug scan (SpotBugs/PMD/Error Prone; see Static Analysis below).
4. Run large-class detection (God Class scan).
5. `diff` selector files — check for duplicated logic.
6. Document findings in `wip/<subsystem>-audit.md`.

### Static Analysis & Tooling

Run before commit (`ant check` where configured); the review toolchain:

| Tool                       | Purpose                                                        |
| -------------------------- | -------------------------------------------------------------- |
| **Checkstyle**             | Style and formatting enforcement                               |
| **SpotBugs / FindBugs**    | Bug patterns: null deref, resource leaks, incorrect `equals`   |
| **PMD**                    | Complexity, dead code, questionable patterns                   |
| **Error Prone**            | Compile-time bug detection (Google)                            |
| **OWASP Dependency-Check** | Known-vulnerable dependencies (for modules with external deps) |

Runtime diagnostics: prefer `jcmd` over `jstack`/`jinfo`/`jmap` (unified since
JDK 8); use JFR + JMC for production profiling (see `docs/DEBUGGING.md`).

---

## AI Agent Working Rules

Rules of engagement for agent-produced changes. The failure modes of generated
code are regressions from context loss, sub-optimal blocks from not knowing the
codebase, and duplication from not searching first:

1. **Read before you write** — read the target file and its neighbors before
   editing; never assume a utility doesn't exist.
2. **Search before you build** — before writing any helper, check
   `net.i2p.data.DataHelper` (canonical source rule above) and `net.i2p.util.*`
   (`Clock`, `Log`, `ConcurrentHashSet`, `LHMCache`, `ByteCache`, `ArraySet`,
   `OrderedProperties`, ...). Duplication is a review blocker.
3. **Mirror existing patterns** — new code should look like its neighbors:
   same style, same logging idiom (`_log.shouldLog`), same error handling.
4. **Keep changes scoped** — no unrelated refactors in the same change;
   touch a file for one reason only. Never "improve" code adjacent to the task.
5. **Extraction must be behavior-preserving** — when extracting logic to
   static helpers (God Class decomposition), the refactor must not change
   results; pin the behavior with tests first (see Testing).
6. **Don't silently change behavior** — any behavioral change needs a test
   that fails before the change; the decision-log rules (see Documented
   Architectural Decisions) are normative, not suggestions.
7. **Verify with the suite** — run `ant test` in the affected module; check
   `git status`/`git diff` before finishing; never stage unrelated files.
8. **Complexity discipline** — CC > 10 extract, > 15 mandatory; > 1000-line
   file is a God Class candidate (see Code Quality Standards).
9. **Don't "fix" working code cosmetically** — style churn on untouched code
   bloats diffs and obscures the real change.
10. **State assumptions** — when environment-specific or unclear, state
    assumptions explicitly rather than silently picking a convention.

---

## Design Patterns & SOLID

Use patterns to answer a *specific instability*, not as decoration:

- **Strategy** — an algorithm that varies and should be swappable (the tunnel
  peer selectors are a real example: `ClientPeerSelector` vs
  `ExploratoryPeerSelector` sharing `TunnelPeerSelector`).
- **Factory (static factory)** — construction that varies by type or
  configuration (Effective Java Item 1).
- **Builder** — many-parameter construction, especially with optional
  parameters (Item 2; `TunnelCreatorConfig` style).
- **The deciding question**: *what is the unstable part of this code?* If the
  algorithm changes → Strategy. If the object graph grows → Builder. If
  nothing varies, a pattern is premature abstraction.

SOLID in practice (from the SOLID-through-Java-examples source):

| Principle | In code                                                                                                      |
| --------- | ------------------------------------------------------------------------------------------------------------ |
| SRP       | A class has one reason to change — extract decision logic from servlets/executors into testable helpers      |
| OCP       | Open for extension, closed for modification — prefer interfaces + composition over `if (type == ...)` chains |
| LSP       | Subtypes must be usable where the base type is declared — comparators must be consistent and transitive      |
| ISP       | Small, focused interfaces over fat ones                                                                      |
| DIP       | Depend on abstractions — e.g. the `TunnelCreatorConfig` base class breaking the tunnel↔pool cycle            |

---

## Documented Architectural Decisions

Key decisions with their rationale (commit hashes from the tunnel subsystem
audit). These are normative — do not silently reverse them:

| Decision                                                            | Rationale                                            | Commit       |
| ------------------------------------------------------------------- | ---------------------------------------------------- | ------------ |
| `getTunnelBuildSuccess()` returns 1.0 (neutral) when no data        | Missing stats ≠ attack; prevents startup attack mode | 1682016670   |
| Dead-ratio peers sort LAST in quality comparator                    | Consistent ordering: Good > Low > Dead               | 25819c1e9c   |
| Client pools skip pre-qual under stress (<40% build success)        | Matches exploratory behavior; prevents pool collapse | 1c6efc4e0be7 |
| `addTunnel` cap: `activeCount >= target+2`                          | Maintains 2-tunnel buffer for replacement builds     | b72a008d6fe0 |
| Remove `hasGoodReplacement` guard from `fail()`                     | Tunnels with >1 failure already skipped by selection | a5a1a379fbd0 |
| FIFO trim recent build IDs (LinkedHashSet)                          | Preserves duplicate detection for late replies       | e6cab1c51b   |
| `preConnectTo` uses `DatabaseLookupMessage`                         | Forces full session handshake, not empty DataMessage | 3dca713ab905 |
| Blocking `Thread.sleep()` in `BuildRequestor` → `NO_TUNNELS` result | Prevents executor thread stall across ALL pools      | 0d4e6efaaa   |
| Rate-limit `selectPeers` failure WARN (3min silence + 1min/pool)    | Reduces log spam during peer discovery               | 808cd42fd0   |
| Ghost peer cooldown snapshotted at mark time (`_ghostUntil`)        | State changes don't extend/shorten active exclusions | 2b4a58ac41   |
| `TunnelCreatorConfig` base class breaks tunnel↔pool cycle           | Data plane no longer depends on pool package         | ece127883e   |

---

## Sources

- Google Java Style Guide — https://google.github.io/styleguide/javaguide.html
- *Effective Java*, 3rd ed. (Bloch) — official book; item summaries at https://gist.github.com/jkmcl/532eb1e453eedb390fc7973a2680e2f9 (unofficial but accurate)
- *Java Is Fast. Your Code Might Not Be.* — https://jvogel.me/posts/2023/java-is-fast-your-code-might-not-be/ (2023, not 2026)
- Oracle: The Collections Framework (Java 17) — https://docs.oracle.com/en/java/javase/17/core/collections-framework.html
- Oracle: Troubleshooting Memory Leaks (Java 17) — https://docs.oracle.com/en/java/javase/17/troubleshoot/troubleshooting-memory-leaks.html
- Oracle: Diagnostic Tools (Java 21) — https://docs.oracle.com/en/java/javase/21/troubleshoot/diagnostic-tools.html
- Oracle: Pattern Matching for switch (JDK 21) — https://docs.oracle.com/en/java/javase/21/language/pattern-matching-switch.html
- Oracle: `Stream` javadoc — https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/Stream.html
- Baeldung: Common Java Concurrency Pitfalls — https://www.baeldung.com/java-common-concurrency-pitfalls
- HowToDoInJava: Java Exception Handling Best Practices — https://howtodoinjava.com/best-practices/java-exception-handling-best-practices/
- Mockito Wiki: How to write good tests — https://github.com/mockito/mockito/wiki/How-to-write-good-tests
- Kodus: Java Code Review Checklist & Best Practices — https://kodus.io/en/java-code-review-checklist-and-best-practices/
- TrinityLogic: Optional Done Right — https://trinitylogic.co.uk/blog/java-optional-patterns-best-practices/
- CodeQL: `java-sensitive-log` — https://codeql.github.com/codeql-query-help/java/java-sensitive-log/
- SLF4J manual (fluent API, 2.0) — https://www.slf4j.org/manual.html
- OWASP: Insecure Deserialization — https://owasp.org/www-community/vulnerabilities/Insecure_Deserialization
- CodingStrain: SOLID Principles Through Java Code Examples — https://codingstrain.com/solid-principles-through-java-code-examples/
- Strategy vs Factory vs Builder (when to use which) — https://tuanhnet.hashnode.dev/
- Guava: Using and Avoiding Null Explained — https://github.com/google/guava/wiki/UsingAndAvoidingNullExplained
- Safeguard.sh: How to Avoid NullPointerException in Java — https://safeguard.sh/resources/blog/how-to-avoid-null-pointer-exception-in-java
- Safeguard.sh: Defensive Java Patterns That Prevent NPEs — https://safeguard.sh/resources/blog/java-nullpointerexception-prevention-patterns
- Stack Overflow: Why use Objects.requireNonNull()? — https://stackoverflow.com/questions/45632920/why-should-one-use-objects-requirenonnull
- Baeldung: Understanding Memory Leaks in Java — https://www.baeldung.com/java-memory-leaks
- HeapHero: Common Memory Leaks in Java & How to Fix Them — https://blog.heaphero.io/common-memory-leaks-in-java-how-to-fix-them/
- Stackify: Understand and Prevent Memory Leaks in a Java Application — https://stackify.com/memory-leaks-java/
- Java Code Geeks: The Object Allocation Tax — https://www.javacodegeeks.com/2026/04/the-object-allocation-tax-why-your-java-service-is-40-gc-and-how-the-jits-escape-analysis-both-helps-and-misleads-you.html
- Submillisecond: Java QuickStart (allocation-free hot path) — https://www.submillisecond.com/cookbook/primers/subms-java-quickstart
- DigitalOcean: Deadlock in Java — https://www.digitalocean.com/community/tutorials/deadlock-in-java-example
- Baeldung: Java Thread Deadlock and Livelock — https://www.baeldung.com/java-deadlock-livelock
- Oracle: Lock Objects (Java Tutorials) — https://docs.oracle.com/javase/tutorial/essential/concurrency/newlocks.html
- Jenkov: Deadlock Prevention — https://jenkov.com/tutorials/java-concurrency/deadlock-prevention.html
- yCrash: Understanding Deadlock in Java — https://blog.ycrash.io/understanding-deadlock-java-causes-solutions/
