# Kademlia (`kademlia/`)

Generic Kademlia distributed hash table implementation, usable with SHA-1, SHA-256, or other key lengths. Originally in `net.i2p.router.networkdb.kademlia`, rewritten as a generic library and moved to core (via i2psnark) so both the router's network database and i2psnark's DHT can share it.

## How It Works

`KBucketImpl` stores peers close to the local node within a distance range; `KBucketSet` manages the full set of buckets covering the key space, splitting buckets as peers are discovered. `XORComparator` orders peers by XOR distance to a target key. `SelectionCollector` gathers the closest peers for a lookup; the `*Trimmer` strategies (`RandomTrimmer`, `RandomIfOldTrimmer`, `RejectTrimmer`) decide which peer to evict when a bucket is full.

## Key Classes

| Class                | Purpose                                         |
| -------------------- | ----------------------------------------------- |
| `KBucketImpl`        | A single bucket of peers within a distance band |
| `KBucketSet`         | The full routing table of buckets               |
| `XORComparator`      | Orders keys/peers by XOR distance               |
| `SelectionCollector` | Collects the closest peers for a lookup         |
| `RandomTrimmer`      | Eviction strategy for full buckets              |
| `RejectTrimmer`      | Rejects new peers when a bucket is full         |

## Docs

- [package.html](package.html) — canonical package description
- [`core/java/src/net/i2p/README.md`](../README.md) — core source map
- Router DHT: `net.i2p.router.networkdb.kademlia`
