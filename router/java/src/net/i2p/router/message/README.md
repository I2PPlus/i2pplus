# Message (`message/`)

Garlic message creation, parsing, and source routing — the router side of I2P's end-to-end message bundling and routing.

## How It Works

Outbound messages are wrapped in garlic: multiple cloves (messages or delivery instructions) encrypted to successive hops. `GarlicMessageBuilder` constructs them; `GarlicMessageParser` validates inbound garlic; `GarlicMessageHandler` routes cloves onward per their delivery instructions.

## Key Classes

| Class                             | Purpose                                            |
| --------------------------------- | -------------------------------------------------- |
| `GarlicMessageBuilder`            | Constructs garlic messages with multiple cloves    |
| `GarlicMessageParser`             | Parses and validates inbound garlic messages       |
| `GarlicMessageHandler`            | Processes and routes garlic messages               |
| `GarlicMessageReceiver`           | Receives and handles garlic message delivery       |
| `GarlicConfig`                    | Configuration for garlic message creation          |
| `PayloadGarlicConfig`             | Configuration for payload-specific garlic messages |
| `OutboundClientMessageJobHelper`  | Manages outbound client message jobs               |
| `OutboundClientMessageOneShotJob` | Single-shot outbound message handling              |
| `SendMessageDirectJob`            | Direct message sending                             |
| `OutboundCache`                   | Caching for outbound message optimization          |

## Docs

- [package.html](package.html) — canonical package description
