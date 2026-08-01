# Client (`client/`)

Router-side implementation of the I2CP (I2P Client Protocol) interface: how applications talk to the router.

## How It Works

`ClientManager` accepts I2CP connections (optionally TLS via `SSLClientListenerRunner`); each client session gets tunnels, destinations, and lease sets managed on its behalf. Messages flow between the client and the network through `ClientMessageEventListener` and the I2CP queues.

## Key Classes

| Class                        | Purpose                                    |
| ---------------------------- | ------------------------------------------ |
| `ClientManager`              | Manages client connections and sessions    |
| `ClientConnectionRunner`     | Handles an individual client connection    |
| `ClientListenerRunner`       | Accepts and manages new client connections |
| `ClientWriterRunner`         | Writes and formats I2CP messages           |
| `ClientMessageEventListener` | Handles client message events              |
| `LeaseRequestState`          | Tracks lease requests for clients          |
| `I2CPMessageQueueImpl`       | Message queue for the client protocol      |
| `CreateSessionJob`           | Handles session creation                   |
| `RequestLeaseSetJob`         | Manages lease set requests                 |
| `LookupDestJob`              | Resolves client destinations               |

## Docs

- [package.html](package.html) — canonical package description
- Client-side API: `net.i2p.client` in `core/java/src/net/i2p/client`
