# i2p-sessionban-nftables(8)

## NAME

`i2p-sessionban-nftables` - sync I2P session bans to nftables named sets

## SYNOPSIS

```
i2p-sessionban-nftables.py [OPTIONS]
```

## DESCRIPTION

This script reads I2P+ sessionban files produced by the I2P+ router and enforces those bans at the network level using nftables.

I2P+'s router maintains a list of peers it has banned for various reasons (sybil attacks, bad handshakes, blocklists, etc.) but only enforces these bans at the application layer. This script bridges that gap by pushing those bans into the kernel's packet filter so that banned IPs are dropped before they consume any application resources.

### Why nftables over iptables?

The iptables approach creates one `DROP` rule per banned IP address. As the ban list grows, the kernel must evaluate each rule sequentially to decide whether to accept or drop a packet — O(n) scaling. With hundreds or thousands of bans, this becomes a performance bottleneck.

The nftables version uses named sets with the `interval` flag. All banned IPs live in a single set, and the kernel performs a single tree-based set membership test per packet — O(log n) regardless of the ban list size. Additionally, nftables can merge contiguous IP ranges into a single interval entry, further reducing set size.

### How it works

Each time the script runs (typically via cron), it:

1. Restores any previously saved nftables ruleset (deletes the existing table first to prevent duplicate rules)
2. Creates the nftables table, chain, and sets if they don't exist (first run only)
3. On first run, migrates any existing iptables `I2P-BANNED` rules into the nftables sets
4. Reads all sessionban files within the configured time window
5. Extracts banned IPs, filtering out bogon/reserved ranges
6. Diffs the wanted IPs against the current nftables set contents
7. Adds new bans and removes expired bans in a single batch operation
8. Cleans up any bogon IPs that may have been previously tracked
9. Saves the updated ruleset to disk for boot persistence (deduplicating any rules before writing)
10. Ensures `/etc/nftables.conf` includes the ruleset file

The script is idempotent — running it multiple times produces the same result. Duplicate bans are silently skipped.

## QUICK START

### 1. Install dependencies

```sh
apt install nftables          # Debian/Ubuntu
yum install nftables          # RHEL/Fedora
```

Ensure the nftables service is enabled:

```sh
systemctl enable nftables
systemctl start nftables
```

### 2. Copy the script

```sh
cp scripts/i2p-sessionban-nftables.py /usr/local/sbin/
chmod 700 /usr/local/sbin/i2p-sessionban-nftables.py
```

### 3. Test with a dry run

```sh
/usr/local/sbin/i2p-sessionban-nftables.py --dry-run
```

This shows what would happen without making any changes. Verify the log output shows expected ban counts.

### 4. Run it for real

```sh
/usr/local/sbin/i2p-sessionban-nftables.py
```

On first run the script will:
- Create the `i2p_bans` nftables table with an input chain and ban sets
- Migrate any existing iptables `I2P-BANNED` rules (if present)
- Save the ruleset to `/etc/nftables/i2p-bans.nft`
- Add `include "/etc/nftables/i2p-bans.nft"` to `/etc/nftables.conf`

### 5. Set up cron

```sh
crontab -e
```

Add the following line to run the script every hour:

```
0 * * * * /usr/local/sbin/i2p-sessionban-nftables.py
```

See CRONTAB CONFIGURATION below for more scheduling options.

### 6. Verify

Check that bans are active:

```sh
i2p-sessionban-nftables.py --list
```

Check the nftables sets directly:

```sh
nft list table inet i2p_bans
```

## REQUIREMENTS

- **Root privileges** — the script manipulates nftables and writes to system paths
- **Python 3.10+** — uses walrus operator (`:=`)
- **nftables** — binary at `/usr/sbin/nft`
- **iptables** — only required if migrating from the iptables version on first run

### Python standard library only

The script uses only Python standard library modules and has no pip dependencies: `argparse`, `concurrent.futures`, `datetime`, `ipaddress`, `os`, `pathlib`, `re`, `subprocess`, `sys`, `tempfile`, `time`, `typing`.

## OPTIONS

`-l`, `--log` *PATH*
: Log file path. Default: `/var/log/i2p-sessionban-iptables.log`. If `/var/log` does not exist, logging is disabled. If the log file cannot be created (permission error), logging falls back to stdout. Set to a persistent path if `/var/log` is on tmpfs and you want logs to survive reboots.

`--ruleset-file` *PATH*
: Nftables ruleset save path for boot persistence. Default: `/etc/nftables/i2p-bans.nft`. After each sync the current ban table is dumped here so it can be reloaded at boot.

`--ban-dir` *PATH*
: Directory containing I2P sessionban files. Default: `/home/i2p/.i2p/sessionbans`. The script reads `sessionbans.txt` in this directory plus any `sessionbans-*.txt` files within the time window.

`--tracking-file` *PATH*
: Ban tracking file that records `IP|timestamp` pairs. Default: `/home/i2p/i2p-sessionbans.txt`. This is how the script knows when a ban was first seen and when it should expire. Written atomically (temp file + rename).

`--lock-file` *PATH*
: Lock file to prevent concurrent runs. Default: `/var/run/i2p-sessionban.lock`. Contains the PID of the running process. Stale locks from crashed processes are detected and cleaned up automatically.

`--ban-duration` *DURATION*
: How long a ban persists after its last appearance in sessionban files. Default: `7d`. See DURATION FORMAT below. A ban is removed when: `now - first_seen >= duration` AND the IP is no longer present in current sessionban files.

`--window-hours` *HOURS*
: Time window for including historical sessionban files. Default: `24`. Only files named `sessionbans-YYYY-MM-DD_HH-MM-SS.txt` with timestamps within this window are parsed. The main `sessionbans.txt` is always included regardless of window.

`--workers` *N*
: Number of parallel file-parsing threads. Default: `4`. Increase if you have many sessionban files and a multi-core system.

`--ipv4-only`
: Only process IPv4 addresses and ignore IPv6. The `banned_ipv6` set and its rule are not created. Use this if your system doesn't have IPv6 connectivity.

`--dry-run`
: Simulate everything without applying changes. No nftables rules are added or removed, no files are written. Useful for previewing what the script would do. The log output shows what would be added and removed.

`--list`
: List all currently banned IPs with age (how long ago the ban was first tracked) and exit. Does not modify anything.

`--list-summary`
: Print a one-line count of banned IPv4 and IPv6 addresses and exit.

`--clean`
: Fully purge all bans and remove all artifacts. See CLEAN MODE below.

`--reset`
: Delete the nftables table, remove the saved ruleset, and restart the nftables service. Preserves tracking data. Use when nftables state is suspected corrupted or `ksoftirqd` is consuming high CPU. See RESET MODE below.

`-h`, `--help`
: Show usage information and exit.

## DURATION FORMAT

The `--ban-duration` argument accepts:

| Format      | Example      | Meaning         |
| ----------- | ------------ | --------------- |
| `Nd`        | `7d`         | N days          |
| `Nh`        | `168h`       | N hours         |
| `Nw`        | `1w`         | N weeks         |
| `Nm`        | `30m`        | N minutes       |
| `forever`   | `forever`    | Never expire    |
| *integer*   | `604800`     | Raw seconds     |

A ban expires when `now - first_seen >= duration` and the IP is no longer in any sessionban file. If an IP keeps appearing in sessionban files, its timestamp is refreshed and the ban is extended.

## CRONTAB CONFIGURATION

### Every hour (recommended)

Run at the top of every hour. This is the standard configuration and keeps bans reasonably fresh.

```
0 * * * * /usr/local/sbin/i2p-sessionban-nftables.py
```

### Every 15 minutes (aggressive)

For routers under heavy attack or experiencing rapid sybil activity.

```
*/15 * * * * /usr/local/sbin/i2p-sessionban-nftables.py
```

### Every 6 hours (conservative)

If your I2P router is stable and you want to minimize cron overhead.

```
0 */6 * * * /usr/local/sbin/i2p-sessionban-nftables.py
```

### With logging to a persistent path

If `/var/log` is on tmpfs and you want to keep logs across reboots:

```
0 * * * * /usr/local/sbin/i2p-sessionban-nftables.py -l /home/i2p/logs/i2p-bans.log
```

### With custom ban duration

Keep bans for 14 days instead of the default 7:

```
0 * * * * /usr/local/sbin/i2p-sessionban-nftables.py --ban-duration 14d
```

### IPv4 only

If your system doesn't use IPv6:

```
0 * * * * /usr/local/sbin/i2p-sessionban-nftables.py --ipv4-only
```

### Suppressing output

Cron sends any stdout/stderr output via email. To suppress this:

```
0 * * * * /usr/local/sbin/i2p-sessionban-nftables.py >/dev/null 2>&1
```

Or log to a file instead:

```
0 * * * * /usr/local/sbin/i2p-sessionban-nftables.py -l /var/log/i2p-bans.log
```

### Combining options

```
0 * * * * /usr/local/sbin/i2p-sessionban-nftables.py --ipv4-only --ban-duration 14d -l /home/i2p/logs/bans.log
```

## SESSIONBAN FILE FORMAT

I2P writes sessionban records to text files in the sessionbans directory. Each line is pipe-delimited:

```
seqnum|hash|ip:port|reason|duration
```

Example lines:

```
12345|ABCD1234567890...|1.2.3.4:5678|Sybil|168H
12346|UNKNOWN|5.6.7.8:1234|Blocklist|7D
```

Fields:

- `seqnum` — sequence number, ignored by the script
- `hash` — I2P router hash, or `UNKNOWN`
- `ip:port` — IP address and port of the banned peer. Supports IPv4 (`1.2.3.4:5678`) and IPv6 (`[::1]:5678`)
- `reason` — human-readable ban reason (used for category filtering)
- `duration` — ban duration in sessionban format (e.g. `168H`, `7D`, `24H?` for monthly)

Lines starting with `#` and blank lines are skipped.

### Sessionban files

The script reads:

- `sessionbans.txt` — the current/active ban file, always read
- `sessionbans-YYYY-MM-DD_HH-MM-SS.txt` — historical snapshots, included if within `--window-hours`

## BAN CATEGORIES

Bans are grouped by reason for logging purposes. All categories are unified into a single ban set — the categories are informational only.

| Category            | Reason pattern               | Description                               |
| ------------------- | ---------------------------- | ----------------------------------------- |
| `long`              | (duration >= 4h)             | Long-duration bans regardless of reason   |
| `xg`                | "XG Router"                  | XG-type routers                           |
| `lu`                | "LU Router"                  | LU-type routers                           |
| `old_slow`          | "Old and Slow"               | Outdated or slow routers                  |
| `bad_handshake`     | "Bad NTCP Handshake"         | NTCP protocol failures                    |
| `blocklist`         | "Blocklist"                  | Known blocklisted peers                   |
| `sybil`             | "Sybil"                      | Suspected sybil routers                   |
| `no_version`        | "No version in RouterInfo"   | Malformed router info                     |

The script logs counts for each category on every run, which can help identify attack patterns over time.

## BOGON FILTERING

Bogon (non-routable, reserved, or private) IPs are automatically excluded from bans. These addresses should never appear as external peers and their presence usually indicates a parsing error or misconfiguration.

**Filtered IPv4 ranges:**

| Range              | Description          |
| ------------------ | -------------------- |
| 127.0.0.0/8        | Loopback             |
| 10.0.0.0/8         | Private (RFC 1918)   |
| 172.16.0.0/12      | Private (RFC 1918)   |
| 192.168.0.0/16     | Private (RFC 1918)   |
| 169.254.0.0/16     | Link-local           |
| 224.0.0.0/4        | Multicast            |
| 0.0.0.0/8          | Unspecified          |
| 100.64.0.0/10      | CGNAT (RFC 6598)     |

**Filtered IPv6 ranges:**

| Range              | Description          |
| ------------------ | -------------------- |
| ::1/128            | Loopback             |
| ::/128             | Unspecified          |
| fe80::/10          | Link-local           |
| ff00::/8           | Multicast            |
| 2001:db8::/32      | Documentation        |
| fc00::/7           | Unique-local         |
| fec0::/10          | Site-local           |

On each run the script also scans the existing tracking file and nftables sets for any bogon IPs that may have slipped through previously, and removes them.

## NFTABLES STRUCTURE

The script creates and manages the following nftables objects:

```
table inet i2p_bans {
    set banned_ipv4 {
        type ipv4_addr
        flags interval
    }

    set banned_ipv6 {
        type ipv6_addr
        flags interval
    }

    chain input {
        type filter hook input priority -10; policy accept;
        ip saddr @banned_ipv4 drop
        ip6 saddr @banned_ipv6 drop
    }
}
```

Key details:

- **Table name:** `i2p_bans` in the `inet` family (handles both IPv4 and IPv6)
- **Chain priority:** `-10` (evaluated early, before most other rules)
- **Chain policy:** `accept` (only explicitly banned IPs are dropped)
- **Set type:** `ipv4_addr` / `ipv6_addr` with `interval` flag
- **The `interval` flag** allows nftables to merge adjacent IPs into CIDR-like ranges, reducing set size and improving lookup performance

The script owns this table exclusively. Other nftables rules (if any) live in separate tables and are not affected.

### Checking nftables state directly

```sh
# Show the full table
nft list table inet i2p_bans

# Show just the IPv4 ban set
nft list set inet i2p_bans banned_ipv4

# Show just the IPv6 ban set
nft list set inet i2p_bans banned_ipv6

# Count banned IPs
nft list set inet i2p_bans banned_ipv4 | grep -o '[0-9]' | wc -l
```

## BOOT PERSISTENCE

Nftables rules live in kernel memory and are lost on reboot. The script handles this automatically:

### How it works

1. **After each sync**, the script dumps the `i2p_bans` table to the ruleset file (default: `/etc/nftables/i2p-bans.nft`).

2. **On first save**, the script appends an `include` directive to `/etc/nftables.conf` if not already present:
   ```
   include "/etc/nftables/i2p-bans.nft"
   ```

3. **On boot**, the nftables systemd service loads `/etc/nftables.conf`, which includes the saved ruleset. All bans are restored immediately at boot, before the first cron run.

4. **On the next cron run**, the script deletes the existing table (if any) and reloads the saved ruleset as a clean slate. This prevents duplicate rules from accumulating. The sync then diffs wanted vs. current IPs and adjusts as needed.

### If /var/log is on tmpfs

The log file (`/var/log/i2p-sessionban-iptables.log`) is lost on reboot if `/var/log` is a tmpfs. This is harmless — logging is informational only. The ruleset file and tracking file should be on persistent storage (they default to `/etc/nftables/` and `/home/i2p/` respectively). If you want logs to survive reboots, specify a persistent path with `-l`:

```
0 * * * * /usr/local/sbin/i2p-sessionban-nftables.py -l /home/i2p/logs/i2p-bans.log
```

### Verifying persistence

After reboot, check that bans are active:

```sh
nft list table inet i2p_bans
```

If the table exists with banned IPs, persistence is working.

## CLEAN MODE

Running with `--clean` performs a complete teardown of all ban state:

```sh
i2p-sessionban-nftables.py --clean
```

This will:

1. Delete the `i2p_bans` nftables table (chain, sets, rules — all removed immediately)
2. Remove any legacy iptables `I2P-BANNED` chain and its jump rule (if present)
3. Delete the tracking file (`/home/i2p/i2p-sessionbans.txt`)
4. Delete the saved ruleset file (`/etc/nftables/i2p-bans.nft`)
5. Remove the `include` line from `/etc/nftables.conf`

After `--clean`, no trace of the ban system remains in nftables or iptables. The next cron run will rebuild everything from scratch.

### Dry run with --clean

You can combine `--clean` with `--dry-run` to preview what would be cleaned without actually doing it:

```sh
i2p-sessionban-nftables.py --clean --dry-run
```

## RESET MODE

If you experience high CPU usage from `ksoftirqd` or suspect nftables state is corrupted, use `--reset`:

```sh
i2p-sessionban-nftables.py --reset
```

This will:

1. Delete the `i2p_bans` nftables table
2. Remove the saved ruleset file (`/etc/nftables/i2p-bans.nft`)
3. Restart the nftables systemd service (reloads `/etc/nftables.conf` cleanly)

The next cron run will rebuild bans from tracking data and save a clean ruleset. Use this instead of `--clean` when you want to keep the tracking data but need a fresh nftables state.

### When to use --reset vs --clean

| Situation                                     | Use         |
| --------------------------------------------- | ----------- |
| ksoftirqd high CPU, suspect duplicate rules   | `--reset`   |
| Nftables state seems corrupted                | `--reset`   |
| Want to completely remove all ban state       | `--clean`   |
| Migrating away from the script                | `--clean`   |

## MIGRATING FROM IPTABLES

If you are currently using `i2p-sessionban-iptables.py`, migration is automatic:

1. Run `i2p-sessionban-nftables.py` for the first time
2. The script detects that the `i2p_bans` table doesn't exist
3. It checks for an existing iptables `I2P-BANNED` chain
4. If found, all DROP rules are imported into the nftables sets
5. The iptables chain is flushed
6. The old iptables script can be removed from cron

No manual migration steps are needed. Both scripts share the same tracking file format, so existing ban data is preserved.

After migration, you can verify the iptables chain is empty:

```sh
iptables -L I2P-BANNED -n
```

And remove it if desired:

```sh
iptables -D INPUT -j I2P-BANNED
iptables -X I2P-BANNED
```

## FILE LOCKING

The script uses a PID-based lock file (`/var/run/i2p-sessionban.lock`) to prevent concurrent execution. Lock acquisition uses `open(O_CREAT | O_EXCL)` for atomic file creation — there is no check-then-write race window. If a second instance starts while the first is running, the atomic create fails and the second instance exits with an error.

If the script crashes without cleaning up the lock file, the next run detects the stale lock (file exists but the recorded PID is no longer alive), removes it, and retries acquisition.

## LOG OUTPUT

Each run produces log output like:

```
[2026-03-21 10:00:01] Starting I2P session ban sync
[2026-03-21 10:00:01] Extracting IPs from sessionban files (window: 24h)...
[2026-03-21 10:00:02] Long-term bans (>=4h): 342, XG Router: 12, LU Router: 5, Old and Slow: 89, Blocklist: 201, Sybil: 45, No version: 3, Bad Handshake: 17
[2026-03-21 10:00:02] Added 23 new bans
[2026-03-21 10:00:02] Removed 8 expired bans
[2026-03-21 10:00:02] Filtered 2 bogon IPs from new bans
[2026-03-21 10:00:02] Saved nftables ruleset to /etc/nftables/i2p-bans.nft
[2026-03-21 10:00:02] Total active bans: 753
[2026-03-21 10:00:02] Done
```

If nothing changed, the output is minimal:

```
[2026-03-21 11:00:01] Starting I2P session ban sync
[2026-03-21 11:00:01] Extracting IPs from sessionban files (window: 24h)...
[2026-03-21 11:00:02] Long-term bans (>=4h): 340, XG Router: 12, LU Router: 5, Old and Slow: 88, Blocklist: 200, Sybil: 44, No version: 3, Bad Handshake: 17
[2026-03-21 11:00:02] Saved nftables ruleset to /etc/nftables/i2p-bans.nft
[2026-03-21 11:00:02] Total active bans: 753
[2026-03-21 11:00:02] Done
```

## EXIT STATUS

`0`
: Success.

`1`
: Error (invalid arguments, missing files, permission denied, lock contention).

## EXAMPLES

List currently banned IPs:

```sh
i2p-sessionban-nftables.py --list
```

Quick summary of ban counts:

```sh
i2p-sessionban-nftables.py --list-summary
```

Preview what would change without modifying anything:

```sh
i2p-sessionban-nftables.py --dry-run
```

Run with a 14-day ban duration and persistent logging:

```sh
i2p-sessionban-nftables.py --ban-duration 14d -l /home/i2p/logs/bans.log
```

IPv4 only, no IPv6:

```sh
i2p-sessionban-nftables.py --ipv4-only
```

Purge everything and start fresh:

```sh
i2p-sessionban-nftables.py --clean
```

Reset nftables state (keeps tracking data):

```sh
i2p-sessionban-nftables.py --reset
```

## FILES

| Path                                           | Description                                  | Persistent?        |     |
| ---------------------------------------------- | -------------------------------------------- | ------------------ | --- |
| `/home/i2p/.i2p/sessionbans/`                  | Sessionban files directory                   | Yes (I2P data)     |     |
| `/home/i2p/.i2p/sessionbans/sessionbans.txt`   | Current sessionban file                      | Yes                |     |
| `/home/i2p/i2p-sessionbans.txt`                | Ban tracking file (IP                        | timestamp)         | Yes |
| `/var/log/i2p-sessionban-iptables.log`         | Log file                                     | Depends on /var    |     |
| `/var/run/i2p-sessionban.lock`                 | Lock file                                    | No (tmpfs OK)      |     |
| `/etc/nftables/i2p-bans.nft`                   | Saved ruleset for boot persistence           | Yes                |     |
| `/etc/nftables.conf`                           | Nftables boot config (auto-updated)          | Yes                |     |

All paths except `/etc/nftables.conf` can be overridden via CLI options.

## TROUBLESHOOTING

### No bans are being applied

Check that the sessionban files exist and contain valid data:

```sh
ls -la /home/i2p/.i2p/sessionbans/
head /home/i2p/.i2p/sessionbans/sessionbans.txt
```

Run with `--dry-run` to see if any IPs are being extracted.

### Bans disappear after reboot

Verify that `/etc/nftables.conf` contains the include:

```sh
grep i2p-bans /etc/nftables.conf
```

Verify that the ruleset file exists:

```sh
ls -la /etc/nftables/i2p-bans.nft
```

Verify the nftables service is enabled at boot:

```sh
systemctl is-enabled nftables
```

### Script reports lock contention

If the script reports another instance is running but you're sure it isn't, remove the stale lock:

```sh
rm /var/run/i2p-sessionban.lock
```

### Performance is slow with many bans

Increase the number of workers for parallel file parsing:

```sh
i2p-sessionban-nftables.py --workers 8
```

If you have hundreds of sessionban files, consider reducing `--window-hours` to parse fewer files.

### Verifying nftables is actually dropping packets

```sh
# List the ban set
nft list set inet i2p_bans banned_ipv4

# Watch dropped packets in real time
nft monitor
```

### ksoftirqd using high CPU

If `ksoftirqd` consumes excessive CPU, the nftables chain may have accumulated duplicate rules (e.g. from a bug in an earlier version of the script). Check for duplicates:

```sh
nft list table inet i2p_bans | grep 'saddr @banned_ipv4' | wc -l
```

If this returns more than 1, duplicates exist. Run `--reset` to tear down and rebuild:

```sh
i2p-sessionban-nftables.py --reset
```

This deletes the table, removes the saved ruleset, and restarts nftables. The next cron run rebuilds bans from a clean state.

## SEE ALSO

`nft(8)`, `iptables(8)`, `crontab(5)`
