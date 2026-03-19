Minimal I2P+ installation configuration files.

These configs disable or reduce the following:
  - No local eepsite (Jetty web server disabled)
  - No SAM bridge
  - No I2PSnark
  - No SusiDNS / SusiMail
  - No POP3/SMTP mail tunnels

And reduce the following:
  - JVM heap size (256MB, 64MB init)
  - Logging file size (128KB, 2 rotations)
  - Tunnel length (2 hops)
  - Bandwidth (32 KBps in/out)
  - Participating tunnels (max 50)
  - Full stats disabled
  - No graphs enabled by default
