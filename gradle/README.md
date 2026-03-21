# gradle/

Gradle build scripts for I2P+. The primary build system is Ant (`build.xml` at the project root); Gradle is used for update packaging and coverage reporting.

## Custom tasks

Run from the project root with `./gradlew <task>`.

| Task                    | Description                                                            |
| ----------------------- | ---------------------------------------------------------------------- |
| `codeCoverageReport`    | Generate JaCoCo coverage report across all subprojects                 |
| `prepUpdateRouter`      | Copy core + router JARs to `pkg-temp/lib`                              |
| `prepUpdateSmall`       | Copy core, router, streaming, console, and i2ptunnel JARs/wars         |
| `prepUpdate`            | Copy all JARs and wars to `pkg-temp/lib`                               |
| `updaterRouter`         | Build router-only update zip                                           |
| `updaterSmall`          | Build small update zip                                                 |
| `updater`               | Build full update zip                                                  |

Standard Gradle tasks (`build`, `jar`, `test`, `clean`, etc.) apply to all 18 subprojects defined in `settings.gradle`.

## Files

- `update.gradle` — Custom update packaging tasks
- `wrapper/` — Gradle wrapper (auto-generated)
