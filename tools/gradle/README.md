# Gradle Build

The Gradle build provides an alternative to Ant for compiling Java modules and building update packages. Build output is redirected to `/tmp/` to keep the workspace clean.

## Prerequisites

- Java SDK 1.8+ (JDK 21 recommended)
- The Gradle wrapper at `gradlew` bootstraps itself — no pre-installed Gradle required

## Build output

All build artifacts go under `/tmp/build-i2p/gradle/`. No `.class` files or jars are written to the workspace.

## Available tasks

### Update packages

| Task                      | Description                                    | Output                                     |
| ------------------------- | ---------------------------------------------- | ------------------------------------------ |
| `./gradlew updater`       | Build full update zip                          | `/tmp/build-i2p/gradle/dist/i2pupdate.zip` |
| `./gradlew updaterSmall`  | Build minimal update (router + essential apps) | `/tmp/build-i2p/gradle/dist/i2pupdate.zip` |
| `./gradlew updaterRouter` | Build router-only update                       | `/tmp/build-i2p/gradle/dist/i2pupdate.zip` |

### Prep tasks (used by updater targets)

| Task               | Description                                                       |
| ------------------ | ----------------------------------------------------------------- |
| `prepUpdate`       | Build all Java jars/wars and stage them into the package temp dir |
| `prepUpdateSmall`  | Build and stage router + essential apps only                      |
| `prepUpdateRouter` | Build and stage router jars only                                  |

### Individual module builds

Each module can be built independently:

| Command                             | Builds                     |
| ----------------------------------- | -------------------------- |
| `./gradlew :core:jar`               | Core library (`i2p.jar`)   |
| `./gradlew :router:jar`             | Router (`router.jar`)      |
| `./gradlew :apps:jetty:jar`         | Jetty servlet support      |
| `./gradlew :apps:routerconsole:jar` | Router console             |
| `./gradlew :apps:i2ptunnel:jar`     | I2P tunnel manager         |
| `./gradlew :apps:sam:jar`           | SAM application            |
| `./gradlew :apps:streaming:jar`     | Streaming library          |
| `./gradlew :apps:ministreaming:jar` | Minimal streaming library  |
| `./gradlew :apps:addressbook:jar`   | Addressbook susidns        |
| `./gradlew :apps:i2psnark:jar`      | I2PSnark bitTorrent client |
| `./gradlew :apps:systray:jar`       | System tray support        |
| `./gradlew :apps:desktopgui:jar`    | Desktop GUI                |
| `./gradlew :apps:susimail:war`      | SUSI mail webapp           |
| `./gradlew :apps:susidns:war`       | SUSI DNS webapp            |
| `./gradlew :apps:i2pcontrol:war`    | JSON-RPC control API       |
| `./gradlew :apps:imagegen:war`      | Image generation webapp    |
| `./gradlew :apps:jrobin:jar`        | JRobin monitoring          |

### Other

| Task              | Description         |
| ----------------- | ------------------- |
| `./gradlew test`  | Run unit tests      |
| `./gradlew clean` | Delete build output |
| `./gradlew jar`   | Build all jars      |
| `./gradlew war`   | Build all wars      |

## Running

```sh
# Build everything and create update zip
./gradlew updater

# Build a single module
./gradlew :core:jar

# Run tests
./gradlew test

# Clean
./gradlew clean
```

## Configuration cache

Configuration cache is enabled in `gradle.properties`. If you see "configuration cache discarded" warnings, the build still works — it just re-computes the cache on the next run.

## Notes

- Tests are not wired into the task graph for update builds (they must be run explicitly with `./gradlew test`)
- The canonical test runner is `ant test` or `tools/scripts/run-tests.sh`
- Build output destination is controlled by `buildDir` in `build.gradle` (default: `/tmp/build-i2p/gradle/`)
