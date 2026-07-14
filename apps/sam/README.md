# SAM Bridge (`sam/`)

SAM (Simple Anonymous Messaging) bridges external applications to I2P over TCP.
Applications connect via a local socket (`127.0.0.1:7656` by default) and use a
simple line-oriented protocol to create I2P destinations, send/receive streams,
datagrams, or raw messages — without embedding the full I2P router.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Protocol Versions](#protocol-versions)
3. [API Reference](#api-reference)
4. [Session Types](#session-types)
5. [Configuration](#configuration)
6. [Security](#security)
7. [Architecture](#architecture)
8. [SendMessageOptions (v3.3+)](#sendmessageoptions-v33)
9. [Limits](#limits)

---

## Quick Start

```python
import socket, time

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect(('127.0.0.1', 7656))

# 1. Handshake
s.sendall(b"HELLO VERSION MIN=3.1 MAX=3.3\n")
resp = s.makefile().readline()  # HELLO REPLY RESULT=OK VERSION=3.3
print(resp)

# 2. Create a transient stream session
s.sendall(b"SESSION CREATE ID=myapp STYLE=STREAM DESTINATION=TRANSIENT\n")
resp = s.makefile().readline()  # SESSION STATUS RESULT=OK DESTINATION=<b64>
print(resp)

# 3. Connect to a peer (steals the control socket in v3)
s.sendall(b"STREAM CONNECT ID=myapp DESTINATION=<base64>\n")

# 4. Now read/write on the socket directly
s.sendall(b"hello from I2P!\n")
```

---

## Protocol Versions

### Version Negotiation

The client sends `HELLO VERSION` with optional `MIN` and `MAX` range:

```
HELLO VERSION MIN=3.1 MAX=3.3
```

The server selects the best mutually-supported version and replies:

```
HELLO REPLY RESULT=OK VERSION=3.3
HELLO REPLY RESULT=NOVERSION
```

| Parameter   | Default                | Description                |
| ----------- | ---------------------- | -------------------------- |
| `MIN`       | `1` (since 0.9.14)     | Minimum acceptable version |
| `MAX`       | `99.99` (since 0.9.14) | Maximum acceptable version |

**Fallback chain:** `3.3` → `3.2` → `3.1` → `3.0` → `2.0` → `1.0`

**Timeout:** 75 seconds for the initial `HELLO VERSION` line.

### Feature Matrix

| Feature                                 | v1       | v2           | v3                               |
| --------------------------------------- | -------- | ------------ | -------------------------------- |
| **STREAM** sessions                     | Yes      | Yes          | Yes                              |
| **DATAGRAM** sessions                   | Yes      | Yes          | Yes                              |
| **RAW** sessions                        | Yes      | Yes          | Yes                              |
| **MASTER/PRIMARY** (multiplexed)        | -        | -            | Yes                              |
| Session nickname (`ID=`)                | -        | -            | Required                         |
| STREAM CONNECT mode                     | Blocking | Non-blocking | Socket-stealing pipe             |
| STREAM RECEIVE flow control             | -        | Yes          | Yes                              |
| Buffer feedback (`BUFFER_FULL`/`READY`) | -        | Yes          | Yes                              |
| Socket stealing (pipe)                  | -        | -            | Yes                              |
| STREAM ACCEPT                           | -        | -            | Yes                              |
| STREAM FORWARD (TCP/SSL)                | -        | -            | Yes                              |
| UDP datagram server (port 7655)         | -        | -            | Yes                              |
| PING/PONG keepalive                     | -        | -            | v3.2+                            |
| PORT info in notifications              | -        | -            | v3.2+                            |
| HEADER option for RAW                   | -        | -            | v3.2+                            |
| SIGNATURE_TYPE in TRANSIENT             | -        | -            | Yes                              |
| AUTH commands                           | -        | -            | Yes                              |
| HELP / QUIT / STOP / EXIT               | -        | -            | Yes                              |
| SendMessageOptions (tags, expiry)       | -        | -            | v3.3+                            |
| I2CP auth (USER/PASSWORD)               | -        | -            | Yes                              |
| Named destinations from sam.keys        | Yes      | Yes          | Yes                              |
| TRANSIENT destinations                  | Yes      | Yes          | Yes                              |
| Inline data on control socket           | Yes      | Yes          | Control only (data via UDP/pipe) |

### v1 — Baseline

The original protocol. Single TCP connection for both control and data. `STREAM
CONNECT` blocks until the connection is established. No flow control feedback.
Session nicknames not supported — stream operations use numeric `ID=<id>`.

### v2 — Non-blocking Connect

`STREAM CONNECT` is non-blocking (returns immediately, background connection via
`StreamConnector`). Adds `STREAM RECEIVE ID=<id> LIMIT=<bytes>|NONE` for
receive-side flow control. `STREAM SEND` returns buffer state
(`OK READY`, `OK BUFFER_FULL`). Sends `STREAM READY_TO_SEND` when buffer
drains.

### v3 — Socket Stealing & Multiplexing

Major redesign. The control socket can be **stolen** by `STREAM CONNECT` or
`STREAM ACCEPT` — the TCP connection becomes a bidirectional pipe for that one
stream. DATAGRAM and RAW data flows over UDP (port 7655) instead of TCP.
Supports MASTER/PRIMARY multiplexed sessions, session nicknames, PING/PONG
keepalive (3.2+), AUTH commands, and per-message options (3.3+).

---

## API Reference

### Notation

```
COMMAND PARAM=VALUE ...
RESPONSE KEYWORD ... PARAM=VALUE ...
```

Lines are `\n`-terminated. Multi-word values are NOT quoted (no `"`). The body
of a SEND/RECEIVED message follows as raw bytes after the header line.

### HELLO (all versions)

**Request:**
```
HELLO VERSION [MIN=<min>] [MAX=<max>]
```

**Response:**
```
HELLO REPLY RESULT=OK VERSION=<version>
HELLO REPLY RESULT=NOVERSION
HELLO REPLY RESULT=I2P_ERROR MESSAGE=<text>
```

### SESSION CREATE (all versions)

**Request:**

```
SESSION CREATE STYLE=<type> DESTINATION=<dest> [key=val] ...   (v1/v2)
SESSION CREATE ID=<nick> STYLE=<type> DESTINATION=<dest> [key=val] ...   (v3)
```

**Parameters:**

| Param            | Required   | Values                                           | Description                       |
| ---------------- | ---------- | ------------------------------------------------ | --------------------------------- |
| `STYLE`          | Yes        | `STREAM`, `DATAGRAM`, `RAW`, `MASTER`, `PRIMARY` | Session type                      |
| `ID`             | v3 only    | any string                                       | Session nickname (required in v3) |
| `DESTINATION`    | Yes        | `TRANSIENT` or base64 private key                | Key material                      |
| `SIGNATURE_TYPE` | No         | e.g. `DSA_SHA1`, `EdDSA_SHA512_Ed25519`          | Key type (v3 TRANSIENT)           |
| `DIRECTION`      | v1/v2      | `CREATE`, `RECEIVE`, `BOTH`                      | Stream direction (default `BOTH`) |

**Responses:**

```
SESSION STATUS RESULT=OK DESTINATION=<base64>
SESSION STATUS RESULT=DUPLICATED_ID
SESSION STATUS RESULT=DUPLICATED_DEST
SESSION STATUS RESULT=INVALID_KEY
SESSION STATUS RESULT=I2P_ERROR [MESSAGE=<text>]
```

**DESTINATION values:**
- `TRANSIENT` — server generates a new ephemeral keypair (not persisted)
- Named key — looked up in `sam.keys` file; auto-created if missing
- Base64-encoded private key stream — used directly, validated in v3

### STREAM CONNECT (all versions)

**Request:**

```
STREAM CONNECT ID=<id> DESTINATION=<dest> [key=val] ...   (v1/v2)
STREAM CONNECT ID=<nick> DESTINATION=<dest> [SILENT=true|false] [FROM_PORT=<n>] [TO_PORT=<n>]   (v3)
```

**Behavior by version:**
- **v1:** Blocks until connected. Response on success/failure.
- **v2:** Returns immediately. Connection proceeds in background.
- **v3:** Steals the control socket. After the header line, the TCP socket
  becomes a bidirectional byte pipe to the peer. `SILENT=true` suppresses
  status messages. `FROM_PORT`/`TO_PORT` specify I2P ports.

**Response (v1/v2):**
```
STREAM STATUS RESULT=OK ID=<id>
STREAM STATUS RESULT=<error> ID=<id> [MESSAGE=<text>]
```

**Notification (v2, async):**
```
STREAM CONNECTED DESTINATION=<b64> ID=<id>
```

### STREAM ACCEPT (v3)

```
STREAM ACCEPT ID=<nick> [SILENT=true|false]
```

Accepts an incoming I2P stream. Steals the control socket as a bidirectional
pipe. Multiple simultaneous accepts allowed (v3.2+).

### STREAM FORWARD (v3)

```
STREAM FORWARD ID=<nick> PORT=<n> [HOST=<ip>] [SSL=true|false] [SILENT=true|false]
```

Continuously accepts incoming I2P streams and forwards each to an external
TCP or SSL server at `HOST:PORT`. Runs in a dedicated thread until closed.

### STREAM SEND (v1/v2)

```
STREAM SEND ID=<id> SIZE=<bytes>
<exactly SIZE raw bytes>
```

**Response (v2+):**
```
STREAM SEND ID=<id> RESULT=OK STATE=<state>
STREAM SEND ID=<id> RESULT=FAILED STATE=<state>
```

`STATE` is `READY` or `BUFFER_FULL`.

### STREAM RECEIVE (v2+)

```
STREAM RECEIVE ID=<id> LIMIT=<bytes>|NONE
```

Sets receive-side flow control. When the byte limit is reached, the server
pauses reading until the client sets a new limit.

### STREAM CLOSE (all versions)

```
STREAM CLOSE ID=<id>   (v1/v2 numeric id)
STREAM CLOSE ID=<nick> (v3 nickname)
```

### DATAGRAM SEND (all versions)

```
DATAGRAM SEND DESTINATION=<dest> SIZE=<bytes> [FROM_PORT=<n>] [TO_PORT=<n>]   (v1/v2 inline)
<exactly SIZE raw bytes>
```

**v3 (via UDP, no inline body):**

The DATAGRAM is sent to the `SAMv3DatagramServer` on port 7655 as a UDP
packet (see [v3 UDP format](#v3-udp-datagram-format)).

**Size limit:** 1–31744 bytes (v1/v2), 1–31744 raw payload (v3).

### RAW SEND (all versions)

```
RAW SEND DESTINATION=<dest> SIZE=<bytes> [PROTOCOL=<n>] [FROM_PORT=<n>] [TO_PORT=<n>]   (v1/v2 inline)
<exactly SIZE raw bytes>
```

**v3 (via UDP):** Same as DATAGRAM but uses the RAW session's UDP channel.

With `HEADER=true` (v3.2+), the RAW receiver gets a line prepended:
```
PROTOCOL=<n> FROM_PORT=<n> TO_PORT=<n>
<data>
```

**Size limit:** 1–32768 bytes.

### DEST GENERATE (all versions)

```
DEST GENERATE [SIGNATURE_TYPE=<type>]
```

Generates a keypair without creating a session. Returns:

```
DEST REPLY PUB=<base64> PRIV=<base64>
```

### NAMING LOOKUP (all versions)

```
NAMING LOOKUP NAME=<name>
```

Resolves an I2P name. Special name `ME` returns the current session's destination.

```
NAMING REPLY RESULT=OK NAME=<name> VALUE=<base64>
NAMING REPLY RESULT=KEY_NOT_FOUND NAME=<name>
```

### AUTH (v3)

```
AUTH ENABLE
AUTH DISABLE
AUTH ADD USER=<user> PASSWORD=<pw>
AUTH REMOVE USER=<user>
```

Manages I2CP password authentication. Passwords are hashed with `PasswordManager`
and persisted to the router config.

```
AUTH STATUS RESULT=OK
AUTH STATUS RESULT=I2P_ERROR MESSAGE=<text>
```

### PING / PONG (v3.2+)

```
PING [data]    # server → client (keepalive, every 3 min)
PONG [data]    # client → server (reply)
```

The server sends `PING` if no command is received for 3 minutes. If `PONG` is
not received within the timeout, the server disconnects.

### SESSION ADD / REMOVE (v3, MASTER only)

```
SESSION ADD ID=<nick> STYLE=RAW|DATAGRAM|STREAM PORT=<n> [HOST=<ip>]
  [PROTOCOL=<n>] [FROM_PORT=<n>] [TO_PORT=<n>] [LISTEN_PORT=<n>] [LISTEN_PROTOCOL=<n>]
SESSION REMOVE ID=<nick>
```

Adds or removes a sub-session on a MASTER/PRIMARY session. Each sub-session
listens on a specific protocol/port combination.

### HELP / QUIT (v3)

```
HELP                  → HELP STATUS RESULT=OK MESSAGE=https://geti2p.net/en/docs/api/samv3
QUIT | STOP | EXIT    → ... STATUS RESULT=OK MESSAGE=bye
```

### Inbound Notifications (server → client)

These are sent asynchronously on the control socket (v1/v2) or via the UDP
datagram server (v3).

| Message                                                                              | Versions   | Description                 |
| ------------------------------------------------------------------------------------ | ---------- | --------------------------- |
| `STREAM CONNECTED DESTINATION=<b64> ID=<id>`                                         | v1/v2      | Outbound stream established |
| `STREAM RECEIVED ID=<id> SIZE=<n>\n<data>`                                           | v1/v2/v3   | Incoming stream data        |
| `STREAM CLOSED ID=<id> RESULT=<result>`                                              | v1/v2/v3   | Stream closed               |
| `STREAM STATUS RESULT=<r> ID=<id> [MESSAGE=...]`                                     | v1/v2      | Stream status change        |
| `STREAM SEND ID=<id> RESULT=<r> STATE=<s>`                                           | v2+        | Send buffer feedback        |
| `STREAM READY_TO_SEND ID=<id>`                                                       | v2+        | Buffer freed up             |
| `DATAGRAM RECEIVED DESTINATION=<b64> SIZE=<n> [FROM_PORT=<n>] [TO_PORT=<n>]\n<data>` | All        | Incoming datagram           |
| `RAW RECEIVED SIZE=<n> [PROTOCOL=<n> FROM_PORT=<n> TO_PORT=<n>]\n<data>`             | All        | Incoming raw message        |
| `PING [data]`                                                                        | v3.2+      | Keepalive probe             |

### v3 UDP Datagram Format

In v3, DATAGRAM and RAW sessions use UDP on port 7655 (default). The UDP
payload is:

```
3.<minor> <nick> <base64dest> [PROTOCOL=<n>] [FROM_PORT=<n>] [TO_PORT=<n>]
  [SEND_TAGS=<n>] [TAG_THRESHOLD=<n>] [EXPIRES=<sec>] [SEND_LEASESET=<bool>]\n
<raw data>
```

The first line is space-separated key-value tokens, terminated by `\n`.
Everything after the newline is the raw message body.

---

## Session Types

| Type                 | I2P Protocol             | Description                                 | Direction      |
| -------------------- | ------------------------ | ------------------------------------------- | -------------- |
| `STREAM`             | Streaming lib            | Reliable, ordered, bidirectional            | Full-duplex    |
| `DATAGRAM`           | Repliable datagrams (17) | Fire-and-forget, includes sender dest       | Unidirectional |
| `RAW`                | Raw datagrams (18)       | Low-level, no sender info in payload        | Unidirectional |
| `MASTER` / `PRIMARY` | Multiplexed              | Multiple sub-sessions on one I2P connection | Bidirectional  |

### STREAM

TCP-like reliable streams over I2P. Uses the streaming lib (same as I2PTunnel).
v1 blocks on connect; v2+ is non-blocking; v3 steals the socket as a direct pipe.

Receiver interface: `SAMStreamReceiver`

### DATAGRAM

I2P repliable datagrams (protocol 17). Each received datagram includes the
sender's Destination. Size limited to ~31 KB. In v3, data flows over UDP.

Receiver interface: `SAMDatagramReceiver`

### RAW

I2P raw datagrams (protocol 18). The sender's Destination is NOT included in
the payload — you must track peers yourself. Size limited to 32 KB. In v3,
data flows over UDP with optional `HEADER=true` prefix.

Receiver interface: `SAMRawReceiver`

### MASTER / PRIMARY

v3 only. A multiplexed session that holds multiple sub-sessions (added via
`SESSION ADD`). Each sub-session listens on a specific I2P protocol/port.
Incoming streams are dispatched to the correct sub-session via
`StreamAcceptor`. A MASTER session can combine STREAM, DATAGRAM, and RAW
sub-sessions under one I2P identity.

---

## Configuration

### Command-Line Flags

```
java net.i2p.sam.SAMBridge [-s] [-c config] [keyfile] [host] [port]
```

| Flag        | Description                                           |
| ----------- | ----------------------------------------------------- |
| `-s`        | Enable SSL                                            |
| `-c <file>` | Config file path (default: `sam.config`)              |
| `keyfile`   | File for persistent key storage (default: `sam.keys`) |
| `host`      | TCP listen address (default: `127.0.0.1`)             |
| `port`      | TCP listen port (default: `7656`)                     |

### Config File Properties

| Property                | Default     | Description                         |
| ----------------------- | ----------- | ----------------------------------- |
| `sam.tcp.host`          | `127.0.0.1` | TCP listen address                  |
| `sam.tcp.port`          | `7656`      | TCP listen port                     |
| `sam.udp.host`          | `127.0.0.1` | UDP datagram server address (v3)    |
| `sam.udp.port`          | `7655`      | UDP datagram server port (v3)       |
| `sam.keyfile`           | `sam.keys`  | Persistent destination key store    |
| `sam.useSSL`            | `false`     | Enable SSL on TCP listener          |
| `sam.auth`              | `false`     | Enable I2CP password authentication |
| `sam.auth.<USER>.shash` | —           | SHA-256 hash of user password       |
| `sam.forceFlush`        | `false`     | Force flush on stream sends         |

### SSL

Requires Java 7+ and proper SSL configuration (certs in the router's
keystore). Enabled with `-s` flag or `sam.useSSL=true`.

### Zombie Session Cleanup

Stale SAM sessions (no activity for 10+ minutes) are automatically removed
every 5 minutes by a `SimpleTimer2` task scheduled in `SAMBridge.startThread()`.

---

## Security

- **Default bind:** `127.0.0.1` (localhost only). Binding to `0.0.0.0` without
  SSL+auth generates a warning.
- **SSL:** Full TLS on the TCP listener (`-s` or `sam.useSSL=true`).
- **I2CP authentication:** `sam.auth=true` enables per-user password
  verification via the `SAMSecureSessionInterface`. Passwords are hashed, not
  stored in plaintext.
- **Interactive approval:** The `SAMSecureSessionInterface` allows pluggable
  user-approval dialogs (used on Android/Desktop).
- **Auth management:** `AUTH ADD/REMOVE/ENABLE/DISABLE` commands at runtime (v3).

---

## Architecture

### Package Layout

```
net.i2p.sam/
├── SAMBridge.java              — TCP listener, accept loop, config
├── SAMHandlerFactory.java      — Version negotiation, handler creation
├── SAMHandler.java (abstract)  — Base handler (read, write, close)
├── SAMv1Handler.java           — v1 command dispatch + notifications
├── SAMv2Handler.java           — v2 additions (STREAM RECEIVE)
├── SAMv3Handler.java           — v3 command dispatch, socket stealing, AUTH
├── SAMMessageSess.java         — Session interface
├── SAMMessageSession.java      — Abstract base session (start, send, receive)
├── SAMStreamSession.java       — STREAM session (connect, send, accept)
├── SAMv2StreamSession.java     — v2 non-blocking connect + flow control
├── SAMv3StreamSession.java     — v3 socket stealing, pipe, forward
├── SAMDatagramSession.java     — DATAGRAM session (I2P repliable datagrams)
├── SAMv3DatagramSession.java   — v3 datagram with UDP forwarding
├── SAMRawSession.java          — RAW session (I2P raw datagrams)
├── SAMv3RawSession.java        — v3 raw with UDP + HEADER option
├── MasterSession.java          — MASTER/PRIMARY multiplexed session
├── SessionsDB.java             — Thread-safe nickname → SessionRecord map
├── SessionRecord.java          — Per-session metadata (dest, props, handler)
├── SAMv3DatagramServer.java    — UDP server for v3 datagram/raw I/O
├── SAMSecureSessionInterface   — Pluggable auth approval interface
├── SAMSecureSession.java       — Default password-hash auth
```

### Handler Hierarchy

```
SAMHandler (abstract)
  └── SAMv1Handler
        └── SAMv2Handler
```

`SAMv3Handler` extends `SAMv1Handler` directly, inheriting all v1 protocol
handling and adding v3-specific dispatch. Created by `SAMHandlerFactory` based
on the negotiated version.

### Session Class Hierarchy

```
SAMMessageSess (interface)
└── SAMMessageSession (abstract) — base session with I2PSession management
    ├── SAMRawSession — raw datagrams (protocol 18)
    │   └── SAMv3RawSession — + UDP forwarding
    ├── SAMDatagramSession — repliable datagrams (protocol 17)
    │   └── SAMv3DatagramSession — + UDP forwarding
    └── SAMStreamSession — TCP-like streaming
        ├── SAMv2StreamSession — + non-blocking connect, flow control
        └── SAMv3StreamSession — + socket stealing, pipe, forward
```

`MasterSession` extends `SAMv3StreamSession` and implements
`SAMDatagramReceiver`, `SAMRawReceiver`, `SAMMessageSess`, and
`I2PSessionMuxedListener`.

### SessionsDB

A global singleton on `SAMv3Handler.sSessionsHash` maps nicknames to
`SessionRecord` objects. Used for session lookup on every v3 command. Stale
entries are cleaned every 5 minutes by a timer in `SAMBridge.startThread()`.

### Socket Stealing (v3)

When a v3 `STREAM CONNECT` or `STREAM ACCEPT` executes:

1. `SAMv3Handler.stealSocket()` marks the handler as `stolenSocket = true`
2. Disables the socket timeout
3. The main `handle()` loop stops (the finally block skips socket close)
4. Two `Pipe` threads are spawned (client→I2P and I2P→client) using the raw
   `SocketChannel`
5. The client's TCP socket becomes a transparent bidirectional pipe to the
   I2P stream

### Thread Model

- **Listener thread:** `SAMBridge.run()` accepts TCP connections, spawns handler threads
- **Handler threads:** One per SAM client (runs `SAMv1/v2/v3Handler.handle()`)
- **Pipe threads:** Two per v3 STREAM connection (bidirectional byte copy)
- **UDP server thread:** `SAMv3DatagramServer` handles all v3 datagram/raw UDP I/O
- **Session cleanup timer:** 5-minute `SimpleTimer2` event for stale session removal

### Receiver Interfaces

Sessions push inbound data to the handler via these interfaces:

```java
interface SAMStreamReceiver {
    void receiveStreamBytes(int id, InputStream data, int size);
}

interface SAMDatagramReceiver {
    void receiveDatagramBytes(Destination sender, byte[] data, int proto,
                              int fromPort, int toPort);
}

interface SAMRawReceiver {
    void receiveRawBytes(byte[] data, int proto, int fromPort, int toPort);
}
```

### I2CP Integration

Each SAM session creates an `I2PSession` (or wraps an existing one). The
session registers as a muxed listener on `(PROTO_ANY, PORT_ANY)` and receives
messages via `I2PSessionMuxedListener.messageReceived()`. I2CP properties
are passed through from `SESSION CREATE` parameters and overridden with SAM
defaults:

```java
i2cpProps.setProperty(I2PClient.PROP_RELIABILITY, "none");
i2cpProps.setProperty("i2cp.fastReceive", "true");
```

---

## SendMessageOptions (v3.3+)

In v3.3+, per-message options can be set in the UDP datagram header or as
session-default properties.

### Per-Message (UDP Header)

```
SEND_TAGS=<n>           # Number of session tags to include (auto-rounded down)
TAG_THRESHOLD=<n>       # Low tag threshold (auto-rounded down)
EXPIRES=<sec>           # Message expiration (seconds from now)
SEND_LEASESET=<bool>    # Send lease set with message
```

### Session-Default Properties

Set these in `SESSION CREATE` parameters (v3):

| Property                 | UDP param       | Default          | Description      |
| ------------------------ | --------------- | ---------------- | ---------------- |
| `crypto.tagsToSend`      | `SEND_TAGS`     | SKM config (~40) | Tags per message |
| `crypto.lowTagThreshold` | `TAG_THRESHOLD` | SKM config (~30) | Low tag warning  |
| `shouldBundleReplyInfo`  | `SEND_LEASESET` | `true`           | Bundle lease set |
| `clientMessageTimeout`   | `EXPIRES`       | none             | Message TTL (ms) |

---

## Limits

| Limit                       | Value                            | Notes                                               |
| --------------------------- | -------------------------------- | --------------------------------------------------- |
| HELLO timeout               | 75 s                             | From connect to first command                       |
| Command timeout             | 60 s (first), 3 min (subsequent) | v3, enforced by socket SO_TIMEOUT                   |
| Stream/RAW send size        | 1–32768 bytes                    | Per message payload                                 |
| Datagram send size          | 1–31744 bytes                    | Slightly smaller than RAW due to repliable overhead |
| UDP datagram payload        | ~60 KB                           | v3, limited by MTU and I2P message size             |
| STREAM forward accept queue | 64                               | v3 `MAX_ACCEPT_QUEUE`                               |
| Stale session expiry        | 10 min                           | Removed by periodic sweeper                         |

---

## Examples

### Python: Minimal v3 Stream

```python
import socket, time

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(75)
s.connect(('127.0.0.1', 7656))

# Handshake
s.sendall(b"HELLO VERSION MIN=3.1 MAX=3.3\n")
print(s.makefile().readline().strip())

# Create session
s.sendall(b"SESSION CREATE ID=chat STYLE=STREAM DESTINATION=TRANSIENT\n")
print(s.makefile().readline().strip())
```

### Python: DATAGRAM (v1)

```python
s.sendall(b"SESSION CREATE STYLE=DATAGRAM DESTINATION=TRANSIENT\n")
resp = s.makefile().readline()
dest = resp.split()[2].split('=')[1]  # extract base64 destination

# Send a datagram
msg = b"hello!"
s.sendall(f"DATAGRAM SEND DESTINATION={dest} SIZE={len(msg)}\n".encode())
s.sendall(msg)
```

### Python: Naming lookup

```python
s.sendall(b"NAMING LOOKUP NAME=me\n")
resp = s.makefile().readline()  # NAMING REPLY RESULT=OK NAME=me VALUE=<b64>
```

### C: v3 Raw with UDP (via pseudo-code)

```c
int fd = socket(AF_INET, SOCK_DGRAM, 0);
struct sockaddr_in sam = { sin_family: AF_INET, sin_port: htons(7655) };

char buf[65536];
int n = snprintf(buf, sizeof(buf),
    "3.3 myrawsession %s PROTOCOL=6 FROM_PORT=0 TO_PORT=0\n%s",
    dest_base64, payload);
sendto(fd, buf, n, 0, &sam, sizeof(sam));
```

---

## Demo Scripts

Python example scripts for all session types are in `demos/`:

```
demos/
├── streamTests/     # STREAM send, receive, forward, server
├── datagramTests/   # DATAGRAM send, receive, forward
└── rawTests/        # RAW send, receive, forward
```

Each directory has `samIn.py` (receiver), `samOut.py` (sender),
`samForward.py` (forwarder), and a `README.txt` with usage examples.

---

## Testing

Smoke test (requires SAM on 127.0.0.1:7656):

```bash
ant testSAM
```

Build and run standalone SAM:

```bash
ant buildSAM
java -cp build/i2p.jar:build/mstreaming.jar:build/streaming.jar:build/sam.jar \
    -Djava.library.path=. net.i2p.sam.SAMBridge
```

Build Java test clients:

```bash
cd apps/sam/java
ant clientjar

# Run sink (receiver) client:
java -cp build/i2p.jar:apps/sam/java/build/samclient.jar \
    net.i2p.sam.client.SAMStreamSink samdest.txt samsinkdir -v 3.2

# Run send client:
java -cp build/i2p.jar:apps/sam/java/build/samclient.jar \
    net.i2p.sam.client.SAMStreamSend samdest.txt samtestdata -v 3.2
```
