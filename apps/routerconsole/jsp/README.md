# Router Console JSP (`jsp/`)

JSP pages for the router console webapp, served from `routerconsole.war` by the embedded Jetty server.

## How It Works

The console renders most pages as JSPs backed by Helper and Renderer classes in `net.i2p.router.web`. Configuration pages that accept POST data submit to Handler classes. The webapp is mounted at the root context by `RouterConsoleRunner` (see `net.i2p.router.RouterConsoleRunner`).

## Pages

- `index.jsp` — console landing page
- `config*.jsp` — configuration pages (advanced, ban, clients, family, i2cp, keyring, etc.)
- `graphs.jsp` / `stats.jsp` — statistics and graphs
- `tunnel*.jsp` — tunnel management
- `peers.jsp` / `netdb.jsp` — peer and network database views

## Docs

- [`routerconsole/README.md`](../README.md) — console module overview
- Web support classes: `net.i2p.router.web`
