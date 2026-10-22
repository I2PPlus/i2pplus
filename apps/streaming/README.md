# Streaming (`streaming/`)

Implementation of I2P's streaming protocol, providing TCP-like reliable, ordered, bidirectional connections over I2P's unreliable garlic messages.

## How It Works

The streaming library abstracts I2P's message-based transport into familiar socket semantics:

1. **Connection** - Client creates an `I2PSocket` via `I2PSocketManager`
2. **Handshake** - Outbound destination created, connected to peer's destination
3. **Data** - Messages fragmented, encrypted, sent through tunnel hops
4. **Ack/Nak** - Acks confirm delivery, naks trigger retransmission
5. **Congestion** - Sliding window limits in-flight messages

## Retransmission Strategy

Unlike TCP's cumulative ACKs, I2P uses selective ACKs per-packet:
- Packets have sequence numbers
- Receiver acks individual packets
- Missing acks trigger retransmission after RTO (retransmission timeout)
- Jitter smoothing prevents rapid retransmit floods

## Key Classes

| Class               | Purpose                              |
| ------------------- | ------------------------------------ |
| `Connection`        | Per-stream state (seq, window, RTT)  |
| `ConnectionManager` | All connections to a destination     |
| `PacketHandler`     | Fragment/reassemble, encrypt/decrypt |
| `MessageHandler`    | garlic message encoding              |

## Differences from TCP

- Handshake runs over an already-established I2P session (SYN / SYN-ACK / ACK),
  not over a new transport connection
- Selective per-packet acks instead of cumulative acks
- No FIN — an explicit close message ends the connection
- Built-in encryption (no TLS needed)

See `ministreaming/` for the client API (`I2PSocket`, `I2PServerSocket`).

## Packages

- `net.i2p.client.streaming.impl` - Connection, ConnectionManager, PacketHandler, schedulers

## Building

```bash
# Gradle
./gradlew :apps:streaming:jar

# Ant (from repo root)
ant buildStreaming
```
