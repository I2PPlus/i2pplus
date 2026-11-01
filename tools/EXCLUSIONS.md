# Scan exclusions

What each scanning platform is told not to report, and where that is enforced.
This page is a reference: the lists are read out of the enforcing files, so to
change an exclusion, change the file, not this page.

| Platform          | Enforced in                                   | Excluded as                                 |
| ----------------- | --------------------------------------------- | ------------------------------------------- |
| PMD (Java)        | `tools/pmd/ruleset-java.xml`                  | `<exclude name="…"/>` per category          |
| PMD (paths)       | `tools/pmd/ruleset-java.xml`                  | `<exclude-pattern>` regexes                 |
| CodeQL            | `tools/template/common.py` `EXCLUDED_RULES`   | `java/…` rule ids                           |
| SpotBugs          | `tools/template/common.py` `EXCLUDED_RULES`   | detector codes                              |
| Path filter, all  | `tools/template/common.py` `EXCLUDE_PATTERNS` | path substrings, matched by `is_excluded()` |

SonarQube has no standing exclusion here: its filter is the `--exclude-rules`
command-line list of `tools/sonarqube/sonarqube-to-html.py`, set per run.

## The one live reader

Nothing reads this page as configuration except `tools/fix-java-issues.py`,
which reads it for file-level exclusions to its `@Override` fixer. A line is
live only when it has three colon-separated fields and the middle one is
exactly `java/missing-override-annotation`; the path the fixer skips is the
**third** field, and the first field is ignored, so it carries the note:

```text
# override fixer:java/missing-override-annotation:core/java/src/net/i2p/foo/Bar.java
# override fixer:java/missing-override-annotation:apps/jetty/*
```

Lines starting with `#` and lines inside a fenced block are skipped by that
reader, so the example above is inert in both forms — keep it that way, an
uncommented example would exclude the paths it names. Every other line here is
prose.

## PMD — `tools/pmd/ruleset-java.xml`

`category/java/security.xml` is enabled with no exclusions.

### `category/java/errorprone.xml`

| Rule                                    | Why                                                                                                                                       |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `EmptyCatchBlock`                       | noisy                                                                                                                                     |
| `AvoidDuplicateLiterals`                | noisy                                                                                                                                     |
| `AvoidLiteralsInIfCondition`            | noisy                                                                                                                                     |
| `AssignmentInOperand`                   | noisy                                                                                                                                     |
| `AvoidCatchingGenericException`         | noisy; the router logs and continues                                                                                                      |
| `NullAssignment`                        | noisy                                                                                                                                     |
| `OneDeclarationPerLine`                 | noisy                                                                                                                                     |
| `UnusedFormalParameter`                 | noisy; interface contracts keep their parameter lists                                                                                     |
| `UnusedAssignment`                      | noisy                                                                                                                                     |
| `UnusedPrivateField`                    | noisy                                                                                                                                     |
| `UnusedPrivateMethod`                   | noisy                                                                                                                                     |
| `ForLoopVariableCount`                  | noisy                                                                                                                                     |
| `AvoidReassigningLoopVariables`         | noisy                                                                                                                                     |
| `DoNotTerminateVM`                      | noisy; the launcher and the wrapper do exit                                                                                               |
| `DoNotCallGarbageCollectionExplicitly`  | noisy                                                                                                                                     |
| `MissingSerialVersionUID`               | noisy                                                                                                                                     |
| `AvoidFieldNameMatchingMethodName`      | noisy                                                                                                                                     |
| `ReturnEmptyCollectionRatherThanNull`   | high noise; house contract is a non-null empty collection                                                                                 |
| `ReplaceJavaUtilDate`                   | high noise                                                                                                                                |
| `AvoidImplicitlyRecompilingRegex`       | high noise                                                                                                                                |
| `ConstructorCallsOverridableMethod`     | high noise                                                                                                                                |
| `UseLocaleWithCaseConversions`          | high noise                                                                                                                                |
| `AvoidUnnecessaryStringBuilderCreation` | high noise                                                                                                                                |
| `AvoidConcatInAppend`                   | high noise                                                                                                                                |
| `ImproperVariableName`                  | high noise; the house prefix is `_field`                                                                                                  |
| `CompareObjectsWithEquals`              | ~95% false positives on enum and constant comparison                                                                                      |
| `AvoidBranchingStatementAsLastInLoop`   | false positives on an intentional terminal break/continue/return; restructuring risks behaviour changes in crypto and transport hot paths |
| `CloseResource`                         | ~95% false positives in networking code: socket streams, streaming connections, NIO channels, lifecycle-managed accept handlers           |
| `AssignmentToNonFinalStatic`            | the idiom is a Tuner-managed static volatile policy field                                                                                 |
| `TestClassWithoutTestCases`             | test infrastructure named `*Test` with no cases                                                                                           |

### `category/java/multithreading.xml`

| Rule                                | Why                                                       |
| ----------------------------------- | --------------------------------------------------------- |
| `AvoidSynchronizedStatement`        | style, not a bug risk                                     |
| `AvoidSynchronizedAtMethodLevel`    | style, not a bug risk                                     |
| `AvoidUsingVolatile`                | style, not a bug risk                                     |
| `DoNotUseThreads`                   | style, not a bug risk                                     |
| `UseConcurrentHashMap`              | style, not a bug risk                                     |
| `DontCallThreadRun`                 | style, not a bug risk                                     |
| `AvoidThreadGroup`                  | style, not a bug risk                                     |
| `OverridingThreadRun`               | the I2P idiom is a Thread subclass used as a task         |

### `tools/pmd/ruleset-java-jpinpoint.xml`

| Rule                                                                 | Why                                                                                                           |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `AvoidTimeUnitConfusion`                                             | noisy                                                                                                         |
| `UsingSuppressWarnings`                                              | the suppressions are the point                                                                                |
| `AvoidUnguardedAssignmentToNonFinalFieldsInObjectsUsingSynchronized` | noisy                                                                                                         |
| `AvoidUnguardedMutableFieldsInObjectsUsingSynchronized`              | noisy                                                                                                         |
| `AvoidMutableStaticFields`                                           | noisy                                                                                                         |
| `AvoidConstantsInInterface`                                          | noisy                                                                                                         |
| `SynchronizingForLocalVars`                                          | noisy                                                                                                         |
| `UnresolvedType`                                                     | noisy                                                                                                         |
| `AvoidIncrementOrDecrementForVolatileField`                          | noisy                                                                                                         |
| `AvoidMutableLists`                                                  | noisy                                                                                                         |
| `AvoidHardcodedConnectionConfig`                                     | noisy                                                                                                         |
| `AvoidDecimalAndChoiceFormatAsField`                                 | noisy                                                                                                         |
| `LetFieldsMeetSerializable`                                          | noisy                                                                                                         |
| `NonComparableMapKeys`                                               | noisy                                                                                                         |
| `NonComparableSetElements`                                           | noisy                                                                                                         |
| `AvoidFieldNameMatchingMethodName`                                   | noisy                                                                                                         |
| `MissingFieldInHashCode`                                             | noisy                                                                                                         |
| `LimitWildcardStaticImports`                                         | noisy                                                                                                         |
| `AvoidMultipleConcatStatements`                                      | noisy                                                                                                         |
| `AvoidLoadingAllFromFile`                                            | noisy                                                                                                         |
| `MissingFieldInEquals`                                               | noisy                                                                                                         |
| `ImplementEqualsHashCodeOnValueObjects`                              | excluded by request; high noise, low signal                                                                   |
| `FieldOfHashCodeMissingInEquals`                                     | excluded by request; high noise, low signal                                                                   |
| `AvoidUnnecessaryStringBuilderCreation`                              | fires on idiomatic `toString()` builders                                                                      |
| `UnconditionalIfStatement`                                           | false positives on dynamic conditions in WorkingDir and I2PTunnelServer                                       |
| `BufferFileStreaming`                                                | blanket rule; I2P file I/O is small-file or buffered, and downstream buffering makes the rest false positives |
| `ImproperVariableName`                                               | house-style clash: the prefix is `_field`                                                                     |
| `AvoidSimpleDateFormat`                                              | house-style clash: single-threaded date helpers                                                               |
| `AvoidDuplicateAssignmentsInCases`                                   | cosmetic switch-case duplication, zero signal                                                                 |

### Path exclusions in the ruleset

Build output and binaries: `/build/`, `/target/`, `/dist/`, `.jar`, `.class`,
`.war`, `/tmp/`, and the generated `* _jsp.java`.

Not scanned: `/test/`, `/demo/`, `/jetty/`, `/wrapper/`, `/pack200/`, `/gnu/`.

Vendored packages: `org/apache/`, `org/bouncycastle/`, `org/cybergarage/`,
`org/minidns/`, `org/json/`, `org/json/simple/`, `org/xlattice/`, `org/rrd4j/`,
`org/jfree/`, `com/southernstorm/`, `com/maxmind/`, `com/mpatric/`,
`com/tomgibara/`, `com/google/`, `com/google/zxing/`, `com/docuverse/`,
`com/thetransactioncompany/`, `com/vuze/`, `io/pack200/`, `edu/internet2/`,
`net/metanotion/`, `net/i2p/apache/`, `org/freenetproject/`.

Named files, each with the reason in a comment above it in the ruleset:
`net/i2p/util/Translate.java` (a `StringBuffer` the `MessageFormat` API
requires), `net/i2p/i2ptunnel/I2PTunnelIRCClient.java` and
`net/i2p/i2ptunnel/socks/I2PSOCKSIRCTunnel.java` (pong buffers shared across
threads, so the `StringBuffer` synchronisation is required), and
`net/i2p/router/web/helpers/NetDbHelper.java` (a comparator over a
method-local class, so it cannot be hoisted to a static field).

## CodeQL — `tools/template/common.py` `EXCLUDED_RULES`

| Rule id                                     | Why                                                  |
| ------------------------------------------- | ---------------------------------------------------- |
| `java/missing-override-annotation`          | added by hand, not a real issue                      |
| `java/useless-null-check`                   | needs complex analysis, low value                    |
| `java/unused-parameter`                     | common in this codebase's patterns                   |
| `java/local-variable-is-never-read`         | common in this codebase's patterns                   |
| `java/deprecated-call`                      | often intentional                                    |
| `java/ignored-error-status-of-call`         | often intentional                                    |
| `java/constant-comparison`                  | often intentional                                    |
| `java/unknown-javadoc-parameter`            | documentation noise (listed twice in the file)       |
| `java/inconsistent-javadoc-throws`          | documentation noise                                  |
| `java/call-to-object-tostring`              | style, not a bug                                     |
| `java/non-sync-override`                    | style, not a bug                                     |
| `java/misleading-indentation`               | style, not a bug                                     |
| `java/missing-case-in-switch`               | style, not a bug                                     |
| `java/sleep-with-lock-held`                 | style, not a bug                                     |
| `java/confusing-method-signature`           | stylistic                                            |
| `java/confusing-method-name`                | stylistic                                            |
| `java/output-resource-leak`                 | ~95% false positives; overlaps PMD `CloseResource`   |
| `java/input-resource-leak`                  | ~95% false positives; overlaps PMD `CloseResource`   |
| `java/missing-space-in-concatenation`       | cosmetic                                             |
| `java/chained-type-tests`                   | cosmetic                                             |
| `java/class-name-matches-super-class`       | cosmetic                                             |

## SpotBugs — `tools/template/common.py` `EXCLUDED_RULES`

All six are excluded as high noise over intentional patterns.

| Detector                                     | Notes                                             |
| -------------------------------------------- | ------------------------------------------------- |
| `DE_MIGHT_IGNORE`                            | a discarded return value is often the point       |
| `RV_RETURN_VALUE_IGNORED_BAD_PRACTICE`       | as above                                          |
| `IS2_INCONSISTENT_SYNC`                      | the router guards state on purpose, per path      |
| `REC_CATCH_EXCEPTION`                        | the router logs and continues                     |
| `VA_FORMAT_STRING_USES_NEWLINE`              | `\n` is the house log separator                   |
| `SWL_SLEEP_WITH_LOCK_HELD`                   | intentional in the build and pool loops           |

## Path filter — `tools/template/common.py` `EXCLUDE_PATTERNS`

`is_excluded()` matches these as substrings of the file URI, for every platform
that goes through `common.py`.

Vendored: `org/cybergarage/`, `org/apache/`, `com/mpatric/`, `org/rrd4j/`,
`org/bouncycastle/`, `io/pack200/`, `com/maxmind/`, `org/minidns/`,
`com/southernstorm/noise/`, `com/southernstorm/`, `org/json/simple/`,
`net/metanotion/`, `apps/imagegen/identicon/`, `apps/imagegen/zxing/`,
`apps/i2psnark/java/src/com/mpatric/`, `apps/pack200/java/src/io/pack200/`,
`apps/jrobin/java/src/org/rrd4j/`, `org/freenetproject/`, `freenet/`,
`com/tomgibara/`, `com/google/zxing/`, `com/docuverse/`,
`com/thetransactioncompany/`, `edu/internet2/`, `com/vuze/`.

Test, demo and temporary: `/test/`, `/demo/`, `/tmp/`, `_jsp.java`.
