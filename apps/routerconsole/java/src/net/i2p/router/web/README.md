# Router Console Web (`web/`)

The router console user interface, implemented in `routerconsole.jar`. Supports the webapp in `routerconsole.war`; the entry point is `RouterConsoleRunner`, started from `clients.config`.

## How It Works

JSP pages in the console use a "Helper" or "Renderer" class to generate HTML. Configuration pages that accept POST data have a "Handler" class to process it — Helpers extend `HelperBase`, Handlers extend `FormHandler`. The base classes live here; most Helpers, Handlers, and Renderers are in the `helpers/` subpackage.

## Key Classes

| Class                    | Purpose                                                  |
| ------------------------ | -------------------------------------------------------- |
| `App`                    | Console webapp entry point and context                   |
| `HelperBase`             | Base class for page helpers                              |
| `FormHandler`            | Base class for POST-processing handlers                  |
| `ConsolePasswordManager` | Console authentication credential handling               |
| `ContextHelper`          | Shared context access for pages                          |
| `NavHelper`              | Navigation rendering                                     |
| `GraphGenerator`         | Stats graph generation                                   |
| `DeadlockDetector`       | Detects stuck router threads from the console            |

## Docs

- [package.html](package.html) — canonical package description
- [`routerconsole/README.md`](../../../../../../../README.md) — console module overview
