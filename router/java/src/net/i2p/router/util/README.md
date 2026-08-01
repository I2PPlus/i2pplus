# Router Utilities (`router/util`)

Utility classes and specialized data structures used throughout the router: collections with bounded/decaying behavior, active queue management, diagnostics, and security helpers.

## How It Works

`CoDelBlockingQueue` and `CoDelPriorityBlockingQueue` apply CoDel (Cooperative Delay) active queue management to bound queue latency under load — used for message queues where low latency matters. `DecayingBloomFilter` and `DecayingHashSet` track recent items (messages, events) with time-based decay instead of unbounded growth. `EventLog` keeps a bounded ring buffer of recent events for diagnostics, and `RouterPasswordManager` handles console authentication credentials.

## Key Classes

| Class                           | Purpose                                                 |
| ------------------------------- | ------------------------------------------------------- |
| `CoDelBlockingQueue`            | Bounded queue with CoDel delay-based management         |
| `CoDelPriorityBlockingQueue`    | Priority variant of the CoDel queue                     |
| `DecayingBloomFilter`           | Time-decaying bloom filter for recent-item tracking     |
| `DecayingHashSet`               | Set whose entries decay after a configurable time       |
| `EventLog`                      | Bounded ring buffer of recent events                    |
| `RouterPasswordManager`         | Console authentication credential handling              |
| `MaskedIPSet`                   | CIDR/IP-range matching for bans and restrictions        |
| `HashDistance`                  | XOR distance computation between hashes                 |
| `CachedIteratorCollection`      | Iteration over a cache with bounded memory              |
| `RandomIterator`                | Random-order iteration over a collection                |

## Docs

- [package.html](package.html) — canonical package description
- [`router/java/src/net/i2p/router/README.md`](../README.md) — router subsystem map
