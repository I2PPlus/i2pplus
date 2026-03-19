#!/usr/bin/env python3
"""
i2p-sessionban-iptables.py
Parses I2P sessionbans and creates/removes iptables rules
Run via cron: 0 * * * * /path/to/i2p-sessionban-iptables.py

Required: root privileges
"""

import argparse
import os
import re
import subprocess
import sys
import time
import fcntl
import tempfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta
from pathlib import Path
from typing import Set, Dict, List, Tuple, Optional
import ipaddress

BAN_DIR = Path("/home/i2p/.i2p/sessionbans")
BAN_FILE = BAN_DIR / "sessionbans.txt"
BAN_TRACKING = Path("/home/i2p/i2p-sessionbans.txt")
CHAIN = "I2P-BANNED"
IPTABLES = "/sbin/iptables"
IP6TABLES = "/sbin/ip6tables"
LOCK_FILE = "/var/run/i2p-sessionban.lock"
NUM_WORKERS = 4
DEFAULT_LOG_FILE = "/var/log/i2p-sessionban-iptables.log"
BAN_WINDOW_HOURS = 24


def normalize_ip(ip: str) -> Optional[str]:
    """Validate and normalize IP address using ipaddress module."""
    if not ip:
        return None
    try:
        addr = ipaddress.ip_address(ip)
        return str(addr)
    except ValueError:
        return None


def is_valid_ip(ip: str) -> bool:
    """Check if string is a valid IP address."""
    if not ip:
        return False
    try:
        ipaddress.ip_address(ip)
        return True
    except ValueError:
        return False


def is_ipv4(ip: str) -> bool:
    """Check if IP is IPv4."""
    try:
        return isinstance(ipaddress.ip_address(ip), ipaddress.IPv4Address)
    except ValueError:
        return False


def is_ipv6(ip: str) -> bool:
    """Check if IP is IPv6."""
    try:
        return isinstance(ipaddress.ip_address(ip), ipaddress.IPv6Address)
    except ValueError:
        return False


def is_bogon_ip(ip: str) -> bool:
    """Check if IP is a bogon (non-routable/reserved)."""
    if not ip:
        return True

    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return True

    if isinstance(addr, ipaddress.IPv4Address):
        return is_bogon_ipv4(addr)
    else:
        return is_bogon_ipv6(addr)


def is_bogon_ipv4(ip: ipaddress.IPv4Address) -> bool:
    """Check if IPv4 is a bogon."""
    if ip.is_loopback:
        return True
    if ip.is_private:
        return True
    if ip.is_link_local:
        return True
    if ip.is_multicast:
        return True
    if ip.is_unspecified:
        return True
    if ip.packed[0] == 100 and 64 <= ip.packed[1] <= 127:
        return True
    return False


def is_bogon_ipv6(ip: ipaddress.IPv6Address) -> bool:
    """Check if IPv6 is a bogon."""
    if ip.is_loopback:
        return True
    if ip.is_unspecified:
        return True
    if ip.is_link_local:
        return True
    if ip.is_multicast:
        return True
    if ip.packed[0] == 0x20 and ip.packed[1] == 0x01 and ip.packed[2] == 0x0d and ip.packed[3] == 0xb8:
        return True
    if ip.packed[0] == 0xfc or ip.packed[0] == 0xfd:
        return True
    if ip.packed[0] == 0x20 and ip.packed[1] == 0x02:
        return True
    return False


def log(msg: str, log_file: Optional[str] = None):
    """Log message to file or stdout."""
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    formatted = f"[{timestamp}] {msg}"
    if log_file:
        try:
            with open(log_file, "a") as f:
                f.write(formatted + "\n")
        except IOError:
            print(formatted)
    else:
        print(formatted)


def parse_duration_hours(duration: str) -> int:
    """Parse duration string to hours."""
    duration = duration.strip().upper()
    if duration == "FOREVER":
        return 876000
    if match := re.match(r"^(\d+)H$", duration):
        return int(match.group(1))
    if match := re.match(r"^(\d+)D$", duration):
        return int(match.group(1)) * 24
    if match := re.match(r"^(\d+)\?$", duration):
        return int(match.group(1)) * 24 * 30
    return 0


def extract_ip_from_field(ipport: str, ipv4_only: bool = False) -> str:
    """Extract IP address from IP:PORT field."""
    if not ipport:
        return ""

    if ipport.startswith("["):
        bracket_end = ipport.find("]")
        if bracket_end > 0:
            ip = ipport[1:bracket_end]
            normalized = normalize_ip(ip)
            if normalized and is_ipv6(normalized):
                return "" if ipv4_only else normalized
        return ""

    if ":" in ipport:
        if ipport.count(":") > 1:
            ip = ipport.rsplit(":", 1)[0]
            normalized = normalize_ip(ip)
            if normalized and is_ipv6(normalized):
                return "" if ipv4_only else normalized
        else:
            ip = ipport.rsplit(":", 1)[0]
    else:
        ip = ipport

    normalized = normalize_ip(ip)
    if normalized and is_ipv4(normalized):
        return normalized
    return ""


def parse_sessionban_line(line: str) -> Optional[Tuple[str, str, str, int]]:
    """Parse a sessionban line. Returns: (hash, ip_port, reason, duration_hours)"""
    line = line.strip()
    if not line or line.startswith("#"):
        return None

    parts = [p.strip() for p in line.split("|")]
    if len(parts) < 4:
        return None

    hash_val = parts[1].strip() if len(parts) > 1 else ""
    ip_port = parts[2].strip() if len(parts) > 2 else ""
    reason = parts[3].strip() if len(parts) > 3 else ""
    duration = parts[4].strip() if len(parts) > 4 else ""

    if hash_val.upper() == "UNKNOWN":
        hash_val = ""

    duration_hours = parse_duration_hours(duration) if duration else 0

    return (hash_val, ip_port, reason, duration_hours)


def parse_file_worker(file_path: Path) -> List[Tuple[str, str, str, int]]:
    """Worker function to parse a single file."""
    results = []
    try:
        with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                parsed = parse_sessionban_line(line)
                if parsed:
                    results.append(parsed)
    except Exception as e:
        log(f"Error reading {file_path}: {e}")
    return results


def get_ban_files(ban_dir: Path, ban_file: Path, window_hours: int = BAN_WINDOW_HOURS) -> List[Path]:
    """Get all sessionban files within the time window."""
    cutoff = datetime.now() - timedelta(hours=window_hours)
    files = [ban_file]

    for f in ban_dir.glob("sessionbans-*.txt"):
        try:
            match = re.search(
                r"sessionbans-(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.", f.name
            )
            if match:
                dt = datetime(
                    int(match.group(1)),
                    int(match.group(2)),
                    int(match.group(3)),
                    int(match.group(4)),
                    int(match.group(5)),
                    int(match.group(6)),
                )
                if dt >= cutoff:
                    files.append(f)
        except Exception:
            continue

    return files


def extract_ips_multithreaded(
    ban_dir: Path,
    ban_file: Path,
    min_hours: int = 0,
    reason_pattern: Optional[str] = None,
    workers: int = NUM_WORKERS,
    ipv4_only: bool = False,
    window_hours: int = BAN_WINDOW_HOURS,
) -> Set[str]:
    """Extract IPs from sessionban files using multiple threads."""
    files = get_ban_files(ban_dir, ban_file, window_hours)
    ips: Set[str] = set()

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(parse_file_worker, f): f for f in files}

        for future in as_completed(futures):
            try:
                results = future.result()
                for hash_val, ip_port, reason, duration_hours in results:
                    if reason_pattern and not re.search(
                        reason_pattern, reason, re.IGNORECASE
                    ):
                        continue

                    if min_hours > 0 and duration_hours < min_hours:
                        continue

                    if min_hours > 0 and not ip_port:
                        continue

                    ip = extract_ip_from_field(ip_port, ipv4_only=ipv4_only)
                    if ip:
                        ips.add(ip)
            except Exception as e:
                log(f"Error processing file: {e}")

    return ips


def extract_all_categories(
    ban_dir: Path,
    ban_file: Path,
    workers: int = NUM_WORKERS,
    ipv4_only: bool = False,
    window_hours: int = BAN_WINDOW_HOURS,
) -> Dict[str, Set[str]]:
    """Extract IPs for all ban categories."""
    categories = {}

    categories["long"] = extract_ips_multithreaded(
        ban_dir, ban_file, min_hours=4, workers=workers, ipv4_only=ipv4_only, window_hours=window_hours
    )

    reason_patterns = {
        "xg": r"XG Router",
        "old_slow": r"Old and Slow",
        "bad_handshake": r"Bad NTCP Handshake",
        "blocklist": r"Blocklist",
        "sybil": r"Sybil",
        "no_version": r"No version in RouterInfo",
    }

    for key, pattern in reason_patterns.items():
        categories[key] = extract_ips_multithreaded(
            ban_dir, ban_file,
            min_hours=0,
            reason_pattern=pattern,
            workers=workers,
            ipv4_only=ipv4_only,
            window_hours=window_hours,
        )

    return categories


def load_tracking(tracking_file: Path) -> Dict[str, int]:
    """Load existing bans from tracking file. Returns {ip: timestamp}."""
    bans = {}
    if tracking_file.exists():
        try:
            with open(tracking_file, "r") as f:
                for line in f:
                    parts = line.strip().split("|")
                    if len(parts) == 2:
                        try:
                            ip = normalize_ip(parts[0])
                            if ip:
                                bans[ip] = int(parts[1])
                        except ValueError:
                            continue
        except Exception as e:
            log(f"Error loading tracking: {e}")
    return bans


def save_tracking(bans: Dict[str, int], tracking_file: Path):
    """Save bans to tracking file atomically using temp file + rename."""
    tracking_file.parent.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", dir=tracking_file.parent, delete=False
        ) as tmp:
            tmp_path = tmp.name
            for ip, timestamp in bans.items():
                tmp.write(f"{ip}|{timestamp}\n")
        os.replace(tmp_path, tracking_file)
    except Exception as e:
        log(f"Error saving tracking: {e}")


def run_iptables(
    args: List[str], log_file: Optional[str] = None, fail_ok: bool = False
) -> Tuple[int, str, str]:
    """Run iptables command and return (returncode, stdout, stderr)."""
    try:
        result = subprocess.run(
            args,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0 and not fail_ok:
            log(f"iptables warning: {' '.join(args)}: {result.stderr}", log_file)
        return result.returncode, result.stdout, result.stderr
    except Exception as e:
        log(f"iptables error: {' '.join(args)}: {e}", log_file)
        return -1, "", str(e)


def init_chain(iptables_bin: str, log_file: Optional[str] = None):
    """Initialize iptables chain if needed."""
    returncode, stdout, stderr = run_iptables(
        [iptables_bin, "-L", CHAIN, "-n"], fail_ok=True
    )
    if returncode != 0:
        run_iptables([iptables_bin, "-N", CHAIN], log_file)

    returncode, _, _ = run_iptables(
        [iptables_bin, "-C", "INPUT", "-j", CHAIN], fail_ok=True
    )
    if returncode != 0:
        run_iptables([iptables_bin, "-I", "INPUT", "-j", CHAIN], log_file)


def get_iptables_binary(ip: str) -> str:
    """Return iptables or ip6tables based on IP version."""
    return IP6TABLES if ":" in ip else IPTABLES


def check_iptables_ban(ip: str, log_file: Optional[str] = None) -> bool:
    """Check if IP is already banned in iptables."""
    iptables_bin = get_iptables_binary(ip)
    returncode, _, _ = run_iptables(
        [iptables_bin, "-C", CHAIN, "-s", ip, "-j", "DROP"],
        fail_ok=True,
    )
    return returncode == 0


def add_iptables_ban(ip: str, log_file: Optional[str] = None) -> bool:
    """Add IP to iptables ban."""
    iptables_bin = get_iptables_binary(ip)
    returncode, _, _ = run_iptables(
        [iptables_bin, "-A", CHAIN, "-s", ip, "-j", "DROP"],
        log_file=log_file,
    )
    return returncode == 0


def remove_iptables_ban(ip: str, log_file: Optional[str] = None):
    """Remove IP from iptables."""
    iptables_bin = get_iptables_binary(ip)
    run_iptables(
        [iptables_bin, "-D", CHAIN, "-s", ip, "-j", "DROP"],
        log_file=log_file,
        fail_ok=True,
    )


def process_bans(
    categories: Dict[str, Set[str]],
    tracking_file: Path,
    ban_duration: int,
    log_file: Optional[str] = None,
    dry_run: bool = False,
) -> Tuple[int, int, int, int, int]:
    """Process all bans - add new, refresh existing, remove expired."""
    now = int(time.time())
    all_ips = set()
    bogon_filtered = 0

    for ips in categories.values():
        for ip in ips:
            if is_bogon_ip(ip):
                bogon_filtered += 1
                log(f"Filtered bogon IP: {ip}", log_file)
            else:
                all_ips.add(ip)

    tracking = load_tracking(tracking_file)
    valid_tracking = {}
    new_bans = 0
    refreshed = 0

    for ip in all_ips:
        if ip in tracking:
            valid_tracking[ip] = now
            refreshed += 1
        else:
            if not check_iptables_ban(ip, log_file):
                if not dry_run:
                    if add_iptables_ban(ip, log_file):
                        log(f"New ban: {ip}", log_file)
                        new_bans += 1
            valid_tracking[ip] = now

    expired_count = 0
    for ip, timestamp in tracking.items():
        age = now - timestamp
        if age >= ban_duration:
            if not dry_run:
                remove_iptables_ban(ip, log_file)
            expired_count += 1
        elif ip not in all_ips:
            if not dry_run:
                remove_iptables_ban(ip, log_file)
            expired_count += 1
        else:
            if ip not in valid_tracking:
                valid_tracking[ip] = timestamp

    if not dry_run:
        save_tracking(valid_tracking, tracking_file)

    return new_bans, refreshed, expired_count, len(valid_tracking), bogon_filtered


def clean_all_bans(
    tracking_file: Path,
    ipv6_enabled: bool = True,
    log_file: Optional[str] = None,
    dry_run: bool = False,
):
    """Remove all bans."""
    log("Cleaning all bans...", log_file)
    for iptables_bin in ([IPTABLES, IP6TABLES] if ipv6_enabled else [IPTABLES]):
        if not dry_run:
            run_iptables([iptables_bin, "-F", CHAIN], log_file)
    if tracking_file.exists() and not dry_run:
        tracking_file.unlink()
    log("Cleared chain and tracking file", log_file)


def check_lock(lock_file: Path) -> bool:
    """Check if lock file exists and process is alive."""
    if not lock_file.exists():
        return False
    try:
        with open(lock_file, "r") as f:
            pid = int(f.read().strip())
        os.kill(pid, 0)
        return True
    except (ValueError, ProcessLookupError, PermissionError, OSError):
        try:
            lock_file.unlink()
            return False
        except OSError:
            return False


def acquire_lock(lock_file: Path) -> bool:
    """Acquire lock file with PID."""
    try:
        if check_lock(lock_file):
            return False
        lock_file.parent.mkdir(parents=True, exist_ok=True)
        with open(lock_file, "w") as f:
            f.write(str(os.getpid()))
        return True
    except IOError:
        return False


def release_lock(lock_file: Path):
    """Release lock file."""
    try:
        if lock_file.exists():
            lock_file.unlink()
    except OSError:
        pass


def main():
    parser = argparse.ArgumentParser(description="I2P+ Session Ban to iptables")
    parser.add_argument(
        "-l", "--log", help=f"Log file path (default: {DEFAULT_LOG_FILE})"
    )
    parser.add_argument("--clean", action="store_true", help="Clean all bans and exit")
    parser.add_argument(
        "--workers",
        type=int,
        default=NUM_WORKERS,
        help=f"Number of parallel workers (default: {NUM_WORKERS})",
    )
    parser.add_argument(
        "--ipv4-only",
        action="store_true",
        help="Only process IPv4 addresses, ignore IPv6",
    )
    parser.add_argument(
        "--ban-dir",
        type=Path,
        default=BAN_DIR,
        help=f"Ban directory (default: {BAN_DIR})",
    )
    parser.add_argument(
        "--tracking-file",
        type=Path,
        default=BAN_TRACKING,
        help=f"Tracking file (default: {BAN_TRACKING})",
    )
    parser.add_argument(
        "--lock-file",
        type=Path,
        default=LOCK_FILE,
        help=f"Lock file (default: {LOCK_FILE})",
    )
    parser.add_argument(
        "--duration",
        type=str,
        default="24h",
        help="Ban duration: Nh (hours), Nd (days), forever (default: 24h)",
    )
    parser.add_argument(
        "--window-hours",
        type=int,
        default=BAN_WINDOW_HOURS,
        help=f"Time window for ban files in hours (default: {BAN_WINDOW_HOURS})",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Simulate changes without applying iptables rules",
    )
    args = parser.parse_args()

    ban_dir = args.ban_dir
    ban_file = ban_dir / "sessionbans.txt"
    tracking_file = args.tracking_file
    lock_file = args.lock_file
    ban_duration = parse_duration_hours(args.duration) * 3600
    if ban_duration <= 0:
        log(f"ERROR: Invalid duration: {args.duration}", log_file)
        sys.exit(1)
    window_hours = args.window_hours

    workers = args.workers
    ipv4_only = args.ipv4_only
    dry_run = args.dry_run
    log_file = args.log if args.log else (DEFAULT_LOG_FILE if os.path.exists("/var/log") else None)

    if log_file:
        log_file = Path(log_file)
        try:
            log_file.parent.mkdir(parents=True, exist_ok=True)
        except PermissionError:
            log_file = None

    if os.geteuid() != 0:
        print("ERROR: This script must be run as root.", file=sys.stderr)
        sys.exit(1)

    if not acquire_lock(lock_file):
        log(f"ERROR: Another instance is already running or stale lock at {lock_file}", args.log)
        sys.exit(1)

    try:
        if args.clean:
            clean_all_bans(tracking_file, ipv6_enabled=not ipv4_only, log_file=log_file, dry_run=dry_run)
            return

        if dry_run:
            log("DRY RUN MODE - No changes will be applied", log_file)

        log("Starting I2P session ban sync", log_file)

        for iptables_bin in ([IPTABLES, IP6TABLES] if not ipv4_only else [IPTABLES]):
            init_chain(iptables_bin, log_file)

        if not ban_file.exists():
            log(f"ERROR: Ban file not found: {ban_file}", log_file)
            sys.exit(1)

        log(f"Extracting IPs from sessionban files (window: {window_hours}h)...", log_file)
        categories = extract_all_categories(
            ban_dir, ban_file, workers=workers, ipv4_only=ipv4_only, window_hours=window_hours
        )

        long_count = len(categories.get("long", set()))
        xg_count = len(categories.get("xg", set()))
        old_slow_count = len(categories.get("old_slow", set()))
        blocklist_count = len(categories.get("blocklist", set()))
        sybil_count = len(categories.get("sybil", set()))
        no_version_count = len(categories.get("no_version", set()))
        bad_handshake_count = len(categories.get("bad_handshake", set()))

        log(
            f"Long-term bans (>=4h): {long_count}, "
            f"XG Router: {xg_count}, "
            f"Old and Slow: {old_slow_count}, "
            f"Blocklist: {blocklist_count}, "
            f"Sybil: {sybil_count}, "
            f"No version: {no_version_count}, "
            f"Bad Handshake: {bad_handshake_count}",
            log_file,
        )

        new_bans, refreshed, expired, total, bogon_filtered = process_bans(
            categories, tracking_file, ban_duration, log_file, dry_run
        )

        tracking = load_tracking(tracking_file)
        cleaned_bogons = 0
        for ip in list(tracking.keys()):
            if is_bogon_ip(ip):
                if not dry_run:
                    remove_iptables_ban(ip, log_file)
                    del tracking[ip]
                cleaned_bogons += 1
                log(f"Removed existing bogon from tracking: {ip}", log_file)
        if cleaned_bogons > 0 and not dry_run:
            save_tracking(tracking, tracking_file)

        for iptables_bin in ([IPTABLES, IP6TABLES] if not ipv4_only else [IPTABLES]):
            returncode, stdout, stderr = run_iptables(
                [iptables_bin, "-L", CHAIN, "-n", "--line-numbers"], fail_ok=True
            )
            if returncode == 0:
                for line in stdout.splitlines()[2:]:
                    parts = line.split()
                    if len(parts) >= 9:
                        src_ip = parts[8].split("/")[0] if "/" in parts[8] else parts[8]
                        if is_bogon_ip(src_ip):
                            if not dry_run:
                                run_iptables(
                                    [iptables_bin, "-D", CHAIN, "-s", src_ip, "-j", "DROP"],
                                    log_file,
                                    fail_ok=True,
                                )
                            cleaned_bogons += 1
                            log(f"Removed existing bogon from {iptables_bin}: {src_ip}", log_file)

        if new_bans > 0:
            log(f"Added {new_bans} new bans", log_file)
        if expired > 0:
            log(f"Removed {expired} expired bans", log_file)
        if bogon_filtered > 0:
            log(f"Filtered {bogon_filtered} bogon IPs from new bans", log_file)
        if cleaned_bogons > 0:
            log(f"Cleaned {cleaned_bogons} existing bogon IPs from tracking", log_file)

        log(f"Total active bans: {total}", log_file)
        log("Done", log_file)

    finally:
        release_lock(lock_file)


if __name__ == "__main__":
    main()
