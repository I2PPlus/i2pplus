# Peer Manager (`peermanager/`)

Peer profiling, capacity calculation, and selection: tracks and evaluates peers, and recommends which to use for tunnel building and other operations.

## How It Works

`ProfileOrganizer` categorizes peer profiles by behavior; `CapacityCalculator` and `SpeedCalculator` score each peer; `PeerSelectionCriteria` selects from the organized set for specific purposes (tunnel building, lookups, etc.). Profiles persist across restarts via `ProfilePersistenceHelper`.

## Key Classes

| Class                   | Purpose                                   |
| ----------------------- | ----------------------------------------- |
| `PeerManager`           | Main peer management and coordination     |
| `PeerProfile`           | Detailed peer information and statistics  |
| `ProfileManagerImpl`    | Manages peer profile data and lifecycle   |
| `ProfileOrganizer`      | Organizes and categorizes peer profiles   |
| `CapacityCalculator`    | Calculates peer capacity and capabilities |
| `SpeedCalculator`       | Evaluates peer speed and performance      |
| `IntegrationCalculator` | Assesses peer integration quality         |
| `PeerSelectionCriteria` | Criteria for peer selection               |
| `PeerTestJob`           | Tests peer connectivity and performance   |
| `DBHistory`             | Tracks peer database interaction history  |
| `TunnelHistory`         | Records peer tunnel participation history |

## Docs

- [package.html](package.html) — canonical package description
