# I2PSnark Standalone

To run I2PSnark in standalone mode, make sure you have an I2P router running
in the background (I2P, I2P+ or i2pd work fine), then run:

- Linux/macOS etc: `launch-i2psnark`
- Windows: `launch-i2psnark.bat`

I2PSnark will be available at: `http://127.0.0.1:8002/i2psnark/`

- To change or disable browser launch at startup, edit `i2psnark-appctx.config`.
- To change the port, edit `jetty-i2psnark.xml`.

## Configuration File Options

Most settings are available in the web interface on the Configuration page.
The following options are configuration-file only - add them to the file
`i2psnark.config` in your standalone install directory (UTF-8 encoded).
The configuration file is re-read every 30 seconds, so changes apply
without a restart.

- `i2psnark.destCycle={true|false}`
  When enabled (the default), running torrents are periodically stopped and
  restarted so their destinations rotate to fresh identities, breaking
  long-lived linkage between your IP address and the torrents' destinations at
  trackers and in the DHT. The cycle runs every 3 hours plus a random delay of
  1 to 60 minutes, is skipped while any torrent is actively downloading, and
  restarts only the torrents that were running.

- `i2psnark.maxConcurrentChecks={n}`
  Maximum number of torrents whose storage is checked at the same time, when
  several torrents start or are rechecked together. Bounds the disk I/O storm
  on startup or after a crash. [Default is 4; restart required to change]

- `i2psnark.maxFilesPerTorrent={n}`
  Maximum number of files per torrent permitted when downloading or creating a
  torrent. Applies to torrents added afterwards; restart not required when
  changed on the I2PSnark configuration page. [Default is 2000]

- `i2psnark.preallocateFiles={true|false}`
  Extend new torrent files to their full size and allocate the space on disk
  immediately when the torrent starts, rather than growing as data is
  written. Prevents a full disk from interrupting downloads and avoids
  fragmentation from pieces arriving out of order. [Default is true]

- `i2psnark.shouldPadFiles={true|false}`
  Add BEP 47 padding files to new torrents created by I2PSnark, so each file
  except the last ends on a piece boundary. Downloading and verifying padded
  torrents is unaffected. [Default is false; changes to the config file are
  applied within 30 seconds without a restart]

- `i2psnark.smartSort={true|false}`
  Sort torrent names using the configured language for correct alphabetical
  order. [Default is false]

- `i2psnark.privatetrackers={tracker1,tracker2,...}`
  Comma-separated list of tracker announce URLs treated as private trackers;
  torrents using them are not shared with other peers via DHT or PEX.

- `i2psnark.trackers={name1,url1,name2,url2,...}`
  Comma-separated name,url pairs defining the default tracker list shown for
  new torrents, overriding the built-in list.

- `i2psnark.banDiscardRatio={true|false}`
  Ban peers that cancel most of what they request. Once a peer has requested
  and cancelled at least 5 MB in total, a cancel ratio above 90% of the bytes
  it requested gets the peer banned (rejected on both incoming and outgoing
  connections) for `i2psnark.banDiscardPeriod` minutes. [Default is true]

- `i2psnark.banDiscardPeriod={n}`
  Duration in minutes of the ban applied to peers with an excessive discard
  ratio. [Default is 60]

- `i2psnark.maxLogMessages={n}`
  Maximum number of messages kept in the web interface message area.
  [Default is 50]

- `i2psnark.tempDir={/path/to/tempdir}`
  When set to a directory path, incomplete downloads are written to a staging
  folder in this directory instead of the data directory, and each file is
  copied into the data directory only when all of its pieces have been
  downloaded. Useful for keeping partial downloads off the main data volume
  (for example on a separate, faster or less heavily used disk). The staging
  directory must have enough free space to hold the largest in-progress
  downloads. When unset or empty, downloads are written directly to the data
  directory as before. Applies to torrents added afterwards; restart not
  required when changed on the I2PSnark configuration page. [Disabled by
  default]

- `i2psnark.verifyThreads={n}`
  Number of worker threads used to verify pieces in parallel during a full
  storage check of a single torrent, e.g. when resuming or rechecking. When
  unset, scales with the CPU count, at least 4. [Default is max(cores / 4, 4);
  read per check, so no restart required]

## Logging

I2PSnark logs to three places:

- The message area of the web interface shows recent events - tracker
  problems, destination cycles, torrent starts and stops. The number of
  messages kept is set by `i2psnark.maxLogMessages` above.

- When running standalone, notable events are also printed to the terminal,
  prefixed with " * " (for example " * No actively downloading torrents -
  cycling destinations..").

- The log files `log-0.txt`, `log-1.txt`, ... in the `logs/` directory of the
  standalone install.

By default only ERROR level messages are written to the log files. To change
this, create a file named `logger.config` in the standalone install directory,
next to `i2psnark.config`. The most useful options:

- `logger.defaultLevel=INFO`
  Level written to the log files - ERROR, WARN, INFO or DEBUG (uppercase).
  [Default is ERROR]

- `logger.record.org.klomp.snark=INFO`
  Level for a single class or package, overriding `logger.defaultLevel` - for
  example, INFO for the i2psnark classes only while keeping the rest at ERROR.

- `logger.minimumOnScreenLevel=ERROR`
  Level shown on the terminal in addition to the log files. [Default is ERROR]

- `logger.logFileSize=5m`
  Maximum size of each log file before it is rotated, e.g. "512K" or "1m".
  [Default is 5m]

- `logger.logRotationLimit=3`
  Number of rotated log files kept (`log-0.txt`, `log-1.txt`, ...).
  [Default is 3]

- `logger.gzip=true`
  Compress rotated log files as `.txt.gz`. [Default is false]

## Adding RPC Support

1. Stop i2psnark standalone if running.

2. a. If you have the i2psnark-rpc plugin installed in your router already,
      copy the file:
      `~/.i2p/plugins/i2psnark-rpc/console/webapps/transmission.war` to the
      `webapps/` directory in your standalone install.

   b. If you do not have the i2psnark-rpc plugin installed, pull the
      `i2p.plugins.i2psnark-rpc` branch from git, build with `ant war`, and
      copy the file `src/build/transmission.war.jar` to the file
      `webapps/transmission.war` in your standalone install.

3. Start i2psnark standalone as usual. The transmission web interface will
   be at `http://127.0.0.1:8002/transmission/web/` or if you have
   `transmission-remote` installed, test with `transmission-remote 8002 -l`.

## License

- I2PSnark is licensed under the GPL v2 and is based on
  Snark (http://www.klomp.org/)
- Jetty is licensed under Apache Software License v2.