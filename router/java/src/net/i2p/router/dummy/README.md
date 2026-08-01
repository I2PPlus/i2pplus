# Dummy (`dummy/`)

Dummy implementations and stub facades for testing and embedded builds — minimal stand-ins for router subsystems that would otherwise require full infrastructure.

## How It Works

Each `Dummy*Facade` (client manager, network database, network database segmentor, peer manager, tunnel manager) implements the subsystem interface with no-op or trivial behavior. `VMCommSystem` provides an in-VM communications stand-in. Used by unit tests to isolate components and by embedded builds that want a router skeleton without real networking.

## Key Classes

| Class                              | Purpose                                          |
| ---------------------------------- | ------------------------------------------------ |
| `DummyClientManagerFacade`         | No-op client manager facade                      |
| `DummyNetworkDatabaseFacade`       | No-op network database facade                    |
| `DummyPeerManagerFacade`           | No-op peer manager facade                        |
| `DummyTunnelManagerFacade`         | No-op tunnel manager facade                      |
| `VMCommSystem`                     | In-VM communications stand-in                    |

## Docs

- [package.html](package.html) — canonical package description
- [`router/java/src/net/i2p/router/README.md`](../README.md) — router subsystem map
