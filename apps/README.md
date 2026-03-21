# I2P Applications (`apps/`)

This directory contains the bundled applications and services that ship with I2P. Each subdirectory is a standalone module with its own build configuration.

| Module                             | Description                                                     |
| ---------------------------------- | --------------------------------------------------------------- |
| [`addressbook/`](addressbook/)     | Hosts.txt subscription fetcher for the local naming service     |
| [`desktopgui/`](desktopgui/)       | System-tray interface for router management                     |
| [`i2pcontrol/`](i2pcontrol/)       | JSON-RPC 2.0 API for external router control                    |
| [`i2psnark/`](i2psnark/)           | I2P-enhanced BitTorrent client with web UI                      |
| [`i2ptunnel/`](i2ptunnel/)         | Configurable client/server tunnels (HTTP, SOCKS, IRC, etc.)     |
| [`imagegen/`](imagegen/)           | QR code and identicon generation for I2P destinations           |
| [`jetty/`](jetty/)                 | Embedded Jetty web server integration layer                     |
| [`jrobin/`](jrobin/)               | RRD4J fork for time-series statistics and graphing              |
| [`ministreaming/`](ministreaming/) | Streaming protocol API and interfaces                           |
| [`pack200/`](pack200/)             | Pack200 JAR compression (forked from OpenJDK)                   |
| [`proxyscript/`](proxyscript/)     | Browser PAC script for `.i2p` proxy routing                     |
| [`routerconsole/`](routerconsole/) | Web-based router control panel                                  |
| [`sam/`](sam/)                     | SAM v3 bridge for external application integration              |
| [`streaming/`](streaming/)         | Streaming protocol implementation (connections, retransmission) |
| [`susidns/`](susidns/)             | Address book and DNS management web UI                          |
| [`susimail/`](susimail/)           | Webmail interface (POP3/SMTP)                                   |
| [`systray/`](systray/)             | System-tray URL launcher for the router console                 |
