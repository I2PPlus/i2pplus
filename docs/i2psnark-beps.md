# i2psnark BitTorrent Extension (BEP) Support

This is the current state of BitTorrent Enhancement Proposal (BEP) support in the
`apps/i2psnark` client (the `org.klomp.snark` package), plus the I2P+/i2psnark
specific deviations from the clearnet specs. Verified against the source at
`apps/i2psnark/java/src/org/klomp/snark/` (2026-09-07).

All BitTorrent traffic in i2psnark runs **inside I2P** — peers are I2P
destinations, "IP addresses" are 32-byte SHA-256 destination hashes, and there is
no clearnet IP/port in any wire format. The spec wording below reflects that.

---

## Protocol messages (BEP 3 core)

Implemented in `Message.java` (`class Message`):

| ID   | Name         | Notes                                                |
| ---- | ------------ | ---------------------------------------------------- |
| -1   | KEEP_ALIVE   | virtual, no wire representation                      |
| 0    | CHOKE        |                                                      |
| 1    | UNCHOKE      |                                                      |
| 2    | INTERESTED   |                                                      |
| 3    | UNINTERESTED |                                                      |
| 4    | HAVE         |                                                      |
| 5    | BITFIELD     |                                                      |
| 6    | REQUEST      |                                                      |
| 7    | PIECE        | supports deferred/cached data download (see nuances) |
| 8    | CANCEL       |                                                      |
| 9    | PORT         | BEP 5 DHT port announce                              |
| 13   | SUGGEST      | BEP 6 Fast extension                                 |
| 14   | HAVE_ALL     | BEP 6                                                |
| 15   | HAVE_NONE    | BEP 6                                                |
| 16   | REJECT       | BEP 6                                                |
| 17   | ALLOWED_FAST | BEP 6                                                |
| 20   | EXTENSION    | BEP 10 (`ExtensionHandler`)                          |
| 21   | HASH_REQUEST | BEP 52 / BEP 30 (merkle) — see note below            |
| 22   | HASHES       | BEP 52 / BEP 30                                      |
| 23   | HASH_REJECT  | BEP 52 / BEP 30                                      |

README-visible switch on the fast/PORT/HASH code paths is in
`PeerConnectionIn.java` (`case Message.HAVE…case Message.ALLOWED_FAST`).

> **BEP 52 / BEP 30 (merkle) caveat**: the message IDs 21–23 and type names are
> defined, but there is **no merkle hash-tree implementation** (no `merkle/`
> package, no HASH_REQUEST handling in `PeerConnectionIn`). The IDs exist for
> wire-compat; the client does not use them. See the "Not implemented" section.

---

## Extension protocol (BEP 10) — `ExtensionHandler.java`

Registered extension IDs (`ID_*`) and type strings (`TYPE_*`):

| ID   | Type string   | Purpose                                                        | BEP               |
| ---- | ------------- | -------------------------------------------------------------- | ----------------- |
| 0    | (handshake)   | `m` map + `p` + `v` + `reqq` + `metadata_size` + `upload_only` | BEP 10            |
| 1    | `ut_metadata` | magnet metadata exchange                                       | BEP 9             |
| 2    | `i2p_pex`     | PEX — **not** `ut_pex` (different payload)                     | BEP 11 adaptation |
| 3    | `i2p_dht`     | DHT port exchange — **not** the standard option bit            | BEP 5 adaptation  |
| 4    | `ut_comment`  | torrent comments                                               | custom (I2P)      |
| 5    | `lt_donthave` | anti-waste "don't have" notifications                          | BEP 54            |

Handshake fields sent (`ExtensionHandler.getHandshake(int,…)`):

- `m` — extension map, always present (even empty, to avoid far-end NPEs)
- `p` — `TrackerClient.PORT`
- `v` — client name (`"I2PSnark"` by default, or spoofed — see ClientID)
- `reqq` — `PeerState.MAX_PIPELINE` (default 32, cap 256)
- `metadata_size` — when metadata is available
- `upload_only` — BEP 21

On handshake receive, peers that don't advertise `ut_metadata` are dropped when
we still need metadata; same for peers with no `metadata_size`.

---

## Metadata exchange (BEP 9) — magnet links

- `MagnetURI.java` — parses `magnet:?xt=urn:btih:...` (base32 or hex infohash),
  `dn`, `xl`, `tr`; also the legacy `maggot://` scheme.
  `MagnetURI.MAGNET_FULL_V2` (`urn:btmh:`) is declared but only partially
  handled (TODO still in the parser — dual v1/v2 param handling).
- `MagnetHandler.java` — HTTP+web-UI integration; accepts a magnet URL query
  param.
- `MagnetState.java` — chunked metadata assembly.
- `ExtensionHandler.handleMetadata()` / `sendRequest` / `sendPiece` implement the
  BEP 9 `msg_type` 0/1/2 (request/data/reject) dance. `MAX_METADATA_SIZE` =
  `Storage.MAX_PIECES * 20 * 5 / 4` (limits a hostile advertised size).
- `PARALLEL_REQUESTS = 8` chunk requests in flight during metadata fetch.
- Trackers embedded in the magnet link (`tr=` params) are added to the torrent.

---

## DHT (BEP 5) — `dht/` package

`KRPC` is a full Kademlia implementation **adapted for I2P**; the deviations are
documented directly in the class Javadoc and are mandatory for the "no IP"
network:

- **Compact peer info is 32 bytes** (SHA-256 destination hash) instead of
  IP:port. There is no peer port in the values plane.
- **Compact node info is 54 bytes** (20-byte node SHA-1 + 32-byte dest hash +
  2-byte query port) instead of 20-byte SHA-1 + 4-byte IP + 2-byte port.
- **Two datagram ports per node**: the query port (repliable/signed datagrams)
  and query-port+1 as the response port (raw/unsigned datagrams for replies,
  errors, and **announce** queries).
- The `nodes` trackerless-torrent dictionary value is a list of 32-byte binary
  destination-hash strings, not `[host, port]` lists.
- A PORT message's UDP port does not need to be pinged before use.

Queries implemented (inbound dispatch at `KRPC.java:1353`, `handleQuery`):
`ping`, `find_node`, `get_peers`, `announce_peer`. Outbound machinery:
`getPeersAndAnnounce`, `announce` (token-gated; if no token, sends `get_peers`
first and waits for a token), `findClosest`.

Security/robustness:

- Tokens are bound to both the requesting node **and** the infohash
  (`dht/TokenKey.java`, SHA-1-derived keys) to prevent replay/cross-use.
- `SECURE_NID` — node IDs are derived securely rather than random.
- Blacklist of unreachable nodes.
- `MAX_INBOUND_TOKEN_AGE` bounds token validity.
- DHT routing is separate from torrent-serving: the main `KRPC` instance can be
  routing-only (`setServeAll(!_multiDest)`, see nuances).

## DHT scrape (BEP 33)

- `dht/BloomFilter.java` implements the BEP 33 bloom filter (parameters via the
  BEP 33 equations). In I2P the inserted values are destination hashes
  (32 bytes) rather than IPs.
- `dht/DHTTracker.java` maintains **cached BEP 33 bloom filters per torrent**
  (one for seeds, one for peers = `BFsd` / `BFpe`) and answers get_peers with
  them (`KRPC.sendNodes`/`sendPeers` when both filters are available).
- Scrape requests set `scrape=1` in get_peers args (KRPC.java:931).

## UDP tracker (BEP 15 / proposal 160) — `UDPTrackerClient.java`

- One instance for all trackers and infohashes.
- Deviations from BEP 15: the announce response carries a **32-byte hash**
  instead of 4-byte IP + 2-byte port (destination hashes).
- Sends **repliable** datagrams for connect/announce and receives **raw**
  datagrams for replies/errors (per the diagram in the class Javadoc).
- Actions: connect(0), announce(1), scrape(2), error(3). Partial seed status
  (BEP 21) is HTTP-only; not defined in the UDP protocol.
- Connection IDs expire after 3 min; `INIT_CONN_ID = 0x41727101980` (the BEP 15
  magic constant).

## HTTP tracker announce — `TrackerClient.java`

- Standard query params (built at `TrackerClient.java:1546`):
  `info_hash`, `peer_id`, `port`, `ip`, `uploaded`, `downloaded`, `left`,
  `compact=1`, `event=` (started/completed/stopped), `numwant`.
- **`ip=` carries our I2P address** (`<b32>.i2p`), not an IP.
- `left` is always sent (`-1` becomes `1` while magnet metadata is pending,
  because postman's tracker requires it).
- Scrape support over HTTP: `TrackerClient.SCRAPE = "scrape"` path (BEP 48).
  Periodic scrapes are throttled/defusable: standard interval, longer deferral
  after a good response, plus random jitter (`scheduleNextScrape`).
- Global announce concurrency cap (`ANNOUNCE_PERMITS = new Semaphore(10)`)
  plus a per-tracker throttle (`MAX_PER_TRACKER_PER_MINUTE = 20` per host per
  minute, shared across torrents). Throttle is enforced before the semaphore so
  queued threads don't hold global permits while waiting for quota. The fetch
  timeout clock only starts once a permit is held.
- Retry: up to 3 attempts of each announce; retries HTTP 504 gateway timeouts.

## Peer ID conventions (BEP 20) — `PeerID.java` / `ClientID.java`

- `PeerID` maps a destination hash to a comparable ID and can build one from a
  base32 `.b32.i2p` string.
- `ClientID.java` has:
  - **Recognition table** — maps raw peer-ID prefixes to client names (used by
    the console peers page), e.g. `TTMt` → `I2P-BT`.
  - **Spoofing profiles** (opt-in via `i2psnark.clientId`): bundled
    peer-ID prefix + tracker User-Agent + BEP 10 `v` string with consistent
    versioning, for Vuze (`-AZ5770-`), BiglyBT (`-BI4100-`), Transmission
    (`-TR4130-`), KTorrent (`-KT2604-`), qBittorrent (`-qB5230-`), libtorrent
    (`-LT1219-`), Tixati (`TIX34`). "random" picks a profile per destination per
    run; empty means identify as I2PSnark. Only clients in our own recognition
    table are spoofable (so the console still shows a real name for our IDs).

## Web seeding (BEP 19) — `WebPeer.java`

- `WebPeer extends Peer`, `IDBytes = "WebSeedBEP19"`, HTTP(s) `.i2p` hosts only
  (`I2PSocketEepGet`).
- Timeouts: header 60s, total 10min, inactivity 2min.
- Dynamic pipeline: `MIN_REQUESTS=8` (128 KB) … `MAX_REQUESTS=128` (2 MB),
  tuned toward a 2-min target fetch time; also `ABSOLUTE_MIN/MAX`.
- **BEP 47**: zeroes are served for padding ranges, written in blocks of 4096.
- Torrent-side webseed metadata is parsed from the `.torrent` (`MetaInfo`:
  "url-list" / BEP 19 list-shaped-or-single handling).

## Private torrents (BEP 27) — `MetaInfo.java`

- `privateTorrent` tri-state: 0 = absent, 1 = private, -1 = explicitly not
  private. The parser accepts `private` as a number (correct) or a legacy string
  `"1"` for compatibility ("BEP 27 doesn't say").

## Upload-only / partial seeding

- Handshake advertises BEP 21 `upload_only=1` when appropriate; partial-seed
  status is honored for HTTP scrapes (udp scrape has no field).
- `TrackerInfo` records `partialSeedCount`.

---

## Not implemented / explicitly unsupported

- **BEP 52 / BEP 30 merkle trees**: message IDs 21–23 exist for wire
  compatibility only; no hash-tree storage, no request handling.
- **Plain `ut_pex` (BEP 11)**: we use `i2p_pex` with a different compact layout;
  the standard `ut_pex` option string is never sent.
- **BEP 51 DHT infohash indexing (`sample_infohashes`)**: not found.
- **BEP 44 / DHT magnet-link extension**: not found.
- **Standard BEP 10 DHT option bit**: we use the `i2p_dht` extension string.
- **Full (non-compact) peer lists in tracker responses (BEP 3 / BEP 23)**:
  compact mode only.
- **Local service discovery (BEP 14, UDP multicast)**: not used — no clearnet
  multicast in I2P.
- **`added.f` (flags) / `dropped` in PEX**: explicitly unsupported in
  `ExtensionHandler` (single `added` string of 32-byte hashes only).
- **Clearnet web seeds**: HTTP seeding only over `.i2p` destinations.

---

## I2P+/i2psnark specific nuances & deviations

### 1. "No IP" wire formats
Everything that would carry an IP:port in clearnet BitTorrent carries a 32-byte
destination hash instead:
- PEX peers = destination hashes (not IP:port, no port on the wire)
- DHT nodes = 54-byte `SHA1(id)+SHA256(dest)+port`
- UDP-tracker announce response = 32-byte hash
- tracker `ip=` param = `<b32>.i2p`
This is the single most important thing to keep in mind when comparing against
the BEPs. The hash on the wire is the **destination hash** (SHA-256 of the
destination), which is what allows a peer to `ConnectionAcceptor`-out to us.

### 2. PEX and DHT extensions are I2P-specific type strings
`i2p_pex` / `i2p_dht` — the option strings differ from the clearnet
`ut_pex`/`ut_metadata`-style names on purpose (comment in source: "Not using the
option bit since the compact format is different"). `ut_metadata` keeps its
standard name because its payload is unchanged.

### 3. PEX payload variant
`sendPEX` writes `added` as a flat concatenation of 32-byte hashes. Inbound,
`added` is decoded the same way; peers equal to ourselves are skipped. Dropped /
flag bits unsupported.

### 4. Two datagram ports per DHT node
The query (repliable) port and response (raw) port differ, unlike clearnet
where one UDP port serves both. Announce queries go on the raw port.

### 5. Routing-only vs serving DHT
With multiple destinations (multi-dest mode) the main `KRPC` exists primarily to
play the DHT game; torrent data serving is delegated to per-info-hash
`TorrentKRPC` instances (see `I2PSnarkUtil.java:1017`,
`setServeAll(!_multiDest)`, and `KRPC.setServeAll`). A routing table + tracker
can be shared between a `KRPC` and `TorrentKRPC` via the `shared` constructor.

### 6. Encryption / transport
There is no BitTorrent-encryption (MSE/PE) layer — that feature is not
standardized as a BEP (published specs are BEP 14 and BEP 23, which cover local
service discovery and compact peer lists respectively). All link security is
provided by I2P SSU/NTCP itself. Client-to-peer connections are ordinary I2P
streams (or signed/raw datagrams for DHT/UDP-tracker).

### 7. `left` emulation for magnets
`left` is never negative on the wire; pending-magnet sends `left=1` and
re-announces once metadata arrives.

### 8. Client spoofing
Opt-in ident spoofing across three surfaces at once (peer ID prefix, HTTP
User-Agent, and BEP 10 handshake `v`) — `ClientID.profile` derives all three
from one `Profile` entry so tracker cross-checks agree. Version strings pinned to
upstream releases verified 2026-08. See `ClientID.java` Javadoc.

### 9. Announcing wait-token behavior
Outbound DHT announces never fire without a valid token. If kicked by a
get_peers response that carried no usable token, they block/wait for one
(`KRPC.announce(byte[],NodeInfo,long,boolean)`).

### 10. PEX refresh gating
PEX is only sent when the receiving peer advertises `i2p_pex`, and only once per
interval; peers sent are those connected since our last PEX send to that peer
(`PeerCoordinator.java:2250`), deduped against webseeds.

### 11. Metadata chunk pipelining cap
`PARALLEL_REQUESTS=8` bounds metadata fetch bandwidth; the state machine
request-fills chunks as they land (`handleMetadata` → `state.getNextRequest()`).

### 12. UDP tracker special-casing
Partial-seed (BEP 21) is HTTP-only; UDP scrape ignores it (proposal 160 has no
field). The BEP 15 magic connect ID is unchanged.

### 13. Web UI / magnet integration
Magnet fetch is integrated into the servlet flow (`MagnetHandler`), and DHT /
UDP-tracker / PEX all funnel into `PeerCoordinator.gotPeers`. `DontHaveMemo`
tracks BEP 54 donthave announcements to avoid re-requesting pieces peers said
they lack.

### 14. Storage / hashing
`Storage` enforces piece-hash verification; `MetaInfo.fast_checkPiece` does a
fast-path piece check. `Storage.MAX_PIECES` bounds both piece count and the
`MAX_METADATA_SIZE` guard, so a torrent advertise can't blow the metadata limit.

---

## Reference map (file → BEP)

| File                                                         | BEP coverage                                                                                       |
| ------------------------------------------------------------ | -------------------------------------------------------------------------------------------------- |
| `Message.java`                                               | BEP 3 messages; BEP 6 fast; BEP 10 EXTENSION; BEP 52 IDs (stub)                                    |
| `ExtensionHandler.java`                                      | BEP 10 handshake; BEP 9 metadata; BEP 11 adaptation (`i2p_pex`); BEP 54; BEP 21; BEP 5 (`i2p_dht`) |
| `MagnetURI.java` / `MagnetState.java` / `MagnetHandler.java` | BEP 9 magnet + metadata                                                                            |
| `dht/KRPC.java`                                              | BEP 5 Kademlia (I2P adaptation); BEP 33 scrape bloom filters                                       |
| `dht/BloomFilter.java` / `dht/DHTTracker.java`               | BEP 33                                                                                             |
| `dht/TokenKey.java`                                          | BEP 5 token security                                                                               |
| `UDPTrackerClient.java`                                      | BEP 15 + proposal 160                                                                              |
| `TrackerClient.java`                                         | HTTP announce + scrape (BEP 48) + BEP 21                                                           |
| `PeerID.java` / `ClientID.java`                              | BEP 20 conventions + spoofing                                                                      |
| `WebPeer.java`                                               | BEP 19 webseed + BEP 47 zero-padding                                                               |
| `MetaInfo.java`                                              | BEP 27 private flag; BEP 19 url-list                                                               |
| `DontHaveMemo.java`                                          | BEP 54                                                                                             |
| `PeerConnectionIn.java`                                      | inbound message dispatch (all message types)                                                       |

---

## BEP references

Official specifications, numbered per
<https://www.bittorrent.org/beps/bep_0000.html>:

| BEP    | Title                                                        | Spec                                                                       | Used by i2psnark as                               |
| ------ | ------------------------------------------------------------ | -------------------------------------------------------------------------- | ------------------------------------------------- |
| BEP 0  | Index of BitTorrent Enhancement Proposals                    | <https://www.bittorrent.org/beps/bep_0000.html>                            | index of all BEPs                                 |
| BEP 3  | The BitTorrent Protocol Specification                        | <https://www.bittorrent.org/beps/bep_0003.html>                            | core messages                                     |
| BEP 5  | DHT Protocol                                                 | <https://www.bittorrent.org/beps/bep_0005.html>                            | Kademlia DHT                                      |
| BEP 6  | Fast Extension                                               | <https://www.bittorrent.org/beps/bep_0006.html>                            | SUGGEST / HAVE_ALL / REJECT / ALLOWED_FAST        |
| BEP 9  | Extension for Peers to Send Metadata Files                   | <https://www.bittorrent.org/beps/bep_0009.html>                            | magnet + ut_metadata                              |
| BEP 10 | Extension Protocol                                           | <https://www.bittorrent.org/beps/bep_0010.html>                            | `EXTENSION` handshake                             |
| BEP 11 | Peer Exchange (PEX)                                          | <https://www.bittorrent.org/beps/bep_0011.html>                            | adapted as `i2p_pex`                              |
| BEP 14 | Local Service Discovery                                      | <https://www.bittorrent.org/beps/bep_0014.html>                            | not implemented (no clearnet multicast in I2P)    |
| BEP 15 | UDP Tracker Protocol for BitTorrent                          | <https://www.bittorrent.org/beps/bep_0015.html>                            | UDP tracker                                       |
| BEP 19 | WebSeeds - HTTP/FTP Seeding                                  | <https://www.bittorrent.org/beps/bep_0019.html>                            | `WebPeer`                                         |
| BEP 20 | Peer ID Conventions                                          | <https://www.bittorrent.org/beps/bep_0020.html>                            | `<peer id>` mapping                               |
| BEP 21 | Extension for Partial Seeds                                  | <https://www.bittorrent.org/beps/bep_0021.html>                            | `upload_only`                                     |
| BEP 23 | Tracker Returns Compact Peer Lists                           | <https://www.bittorrent.org/beps/bep_0023.html>                            | `compact=1`                                       |
| BEP 27 | Private Torrents                                             | <https://www.bittorrent.org/beps/bep_0027.html>                            | `private` flag                                    |
| BEP 30 | Merkle Hash Torrent Extension                                | <https://www.bittorrent.org/beps/bep_0030.html>                            | IDs 21–23 (stub only)                             |
| BEP 33 | DHT Scrapes                                                  | <https://www.bittorrent.org/beps/bep_0033.html>                            | bloom-filter scrape                               |
| BEP 44 | Storing Arbitrary Data in the DHT                            | <https://www.bittorrent.org/beps/bep_0044.html>                            | not implemented                                   |
| BEP 47 | Padding Files and Extended File Attributes                   | <https://www.bittorrent.org/beps/bep_0047.html>                            | webseed zero-padding                              |
| BEP 48 | Tracker Protocol Extension: Scrape                           | <https://www.bittorrent.org/beps/bep_0048.html>                            | HTTP scrape                                       |
| BEP 51 | DHT Infohash Indexing (sample_infohashes)                    | <https://www.bittorrent.org/beps/bep_0051.html>                            | not implemented                                   |
| BEP 52 | The BitTorrent Protocol Specification v2                     | <https://www.bittorrent.org/beps/bep_0052.html>                            | HASH_REQUEST IDs (stub only)                      |
| BEP 54 | The "lt_donthave" Extension                                  | <https://www.bittorrent.org/beps/bep_0054.html>                            | lt_donthave                                       |

Proposal 160 (referred to alongside BEP 15 for the UDP tracker) is an
unofficial draft, not a BEP; see <https://github.com/bittorrent/bittorrent.org/tree/master/beps>.