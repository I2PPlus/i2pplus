#!/usr/bin/env python3
"""
i2p-sessionban-nftables.py
Parses I2P+ session bans and manages nftables named sets for O(log n) ban lookups.
Drop-in replacement for i2p-sessionban-iptables.py — same CLI, same tracking file.
Migrates existing iptables I2P-BANNED rules into nftables sets on first run.

Run via cron: 0 * * * * /path/to/i2p-sessionban-nftables.py

Required: root privileges, nftables, iptables (for migration)
"""

import argparse
import os
import re
import subprocess
import sys
import time
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
TABLE = "i2p_bans"
SET_IPV4 = "banned_ipv4"
SET_IPV6 = "banned_ipv6"
NFT = "/usr/sbin/nft"
IPTABLES = "/sbin/iptables"
IP6TABLES = "/sbin/ip6tables"
LOCK_FILE = "/var/run/i2p-sessionban.lock"
BAN_DURATION = 604800
NUM_WORKERS = 4
DEFAULT_LOG_FILE = "/var/log/i2p-sessionban-iptables.log"
DEFAULT_RULESET_FILE = "/etc/nftables/i2p-bans.nft"
NFTABLES_CONF = "/etc/nftables.conf"
BAN_WINDOW_HOURS = 24


def normalize_ip(ip: str) -> Optional[str]:
    if not ip:
        return None
    try:
        return str(ipaddress.ip_address(ip))
    except ValueError:
        return None


def is_valid_ip(ip: str) -> bool:
    try:
        ipaddress.ip_address(ip)
        return True
    except ValueError:
        return False


def is_ipv4(ip: str) -> bool:
    try:
        return isinstance(ipaddress.ip_address(ip), ipaddress.IPv4Address)
    except ValueError:
        return False


def is_ipv6(ip: str) -> bool:
    try:
        return isinstance(ipaddress.ip_address(ip), ipaddress.IPv6Address)
    except ValueError:
        return False


def is_bogon_ip(ip: str) -> bool:
    if not ip:
        return True
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return True
    if isinstance(addr, ipaddress.IPv4Address):
        return _is_bogon_ipv4(addr)
    return _is_bogon_ipv6(addr)


def _is_bogon_ipv4(ip: ipaddress.IPv4Address) -> bool:
    if ip.is_loopback or ip.is_private or ip.is_link_local or ip.is_multicast or ip.is_unspecified:
        return True
    if ip.packed[0] == 100 and 64 <= ip.packed[1] <= 127:
        return True
    return False


def _is_bogon_ipv6(ip: ipaddress.IPv6Address) -> bool:
    if ip.is_loopback or ip.is_unspecified or ip.is_link_local or ip.is_multicast:
        return True
    p = ip.packed
    if p[0] == 0x20 and p[1] == 0x01 and p[2] == 0x0d and p[3] == 0xb8:
        return True
    if p[0] in (0xfc, 0xfd):
        return True
    if p[0] == 0x20 and p[1] == 0x02:
        return True
    return False


def log(msg: str, log_file: Optional[str] = None):
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


def parse_duration_seconds(duration: str) -> int:
    duration = duration.strip().lower()
    if duration == "forever":
        return 876000 * 3600
    if match := re.match(r"^(\d+)w$", duration):
        return int(match.group(1)) * 7 * 86400
    if match := re.match(r"^(\d+)d$", duration):
        return int(match.group(1)) * 86400
    if match := re.match(r"^(\d+)h$", duration):
        return int(match.group(1)) * 3600
    if match := re.match(r"^(\d+)m$", duration):
        return int(match.group(1)) * 60
    try:
        return int(duration)
    except ValueError:
        return 0


def extract_ip_from_field(ipport: str, ipv4_only: bool = False) -> str:
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
    cutoff = datetime.now() - timedelta(hours=window_hours)
    files = [ban_file]
    for f in ban_dir.glob("sessionbans-*.txt"):
        try:
            match = re.search(
                r"sessionbans-(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})\.", f.name
            )
            if match:
                dt = datetime(
                    int(match.group(1)), int(match.group(2)), int(match.group(3)),
                    int(match.group(4)), int(match.group(5)), int(match.group(6)),
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
    files = get_ban_files(ban_dir, ban_file, window_hours)
    ips: Set[str] = set()
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(parse_file_worker, f): f for f in files}
        for future in as_completed(futures):
            try:
                results = future.result()
                for hash_val, ip_port, reason, duration_hours in results:
                    if reason_pattern and not re.search(reason_pattern, reason, re.IGNORECASE):
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
    categories = {}
    categories["long"] = extract_ips_multithreaded(
        ban_dir, ban_file, min_hours=4, workers=workers, ipv4_only=ipv4_only, window_hours=window_hours
    )
    reason_patterns = {
        "xg": r"XG Router",
        "lu": r"LU Router",
        "old_slow": r"Old and Slow",
        "bad_handshake": r"Bad NTCP Handshake",
        "blocklist": r"Blocklist",
        "sybil": r"Sybil",
        "no_version": r"No version in RouterInfo",
    }
    for key, pattern in reason_patterns.items():
        categories[key] = extract_ips_multithreaded(
            ban_dir, ban_file, min_hours=0, reason_pattern=pattern,
            workers=workers, ipv4_only=ipv4_only, window_hours=window_hours,
        )
    return categories


def load_tracking(tracking_file: Path) -> Dict[str, int]:
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


def run_nft(args: List[str], log_file: Optional[str] = None, fail_ok: bool = False) -> Tuple[int, str, str]:
    try:
        result = subprocess.run([NFT] + args, capture_output=True, text=True)
        if result.returncode != 0 and not fail_ok:
            log(f"nft warning: {' '.join(args)}: {result.stderr}", log_file)
        return result.returncode, result.stdout, result.stderr
    except Exception as e:
        log(f"nft error: {' '.join(args)}: {e}", log_file)
        return -1, "", str(e)


def run_iptables(
    args: List[str], log_file: Optional[str] = None, fail_ok: bool = False
) -> Tuple[int, str, str]:
    try:
        result = subprocess.run(args, capture_output=True, text=True)
        if result.returncode != 0 and not fail_ok:
            log(f"iptables warning: {' '.join(args)}: {result.stderr}", log_file)
        return result.returncode, result.stdout, result.stderr
    except Exception as e:
        log(f"iptables error: {' '.join(args)}: {e}", log_file)
        return -1, "", str(e)


def migrate_from_iptables(ipv4_only: bool = False, log_file: Optional[str] = None) -> int:
    """Import existing iptables I2P-BANNED rules into nftables sets."""
    migrated = 0
    now = int(time.time())

    for iptables_bin, set_name in [(IPTABLES, SET_IPV4)] + ([(IP6TABLES, SET_IPV6)] if not ipv4_only else []):
        returncode, stdout, _ = run_iptables(
            [iptables_bin, "-L", CHAIN, "-n"], fail_ok=True
        )
        if returncode != 0:
            continue

        ips = set()
        for line in stdout.splitlines()[2:]:
            parts = line.split()
            if len(parts) >= 5 and parts[1] == "DROP":
                src_ip = parts[4].split("/")[0] if "/" in parts[4] else parts[4]
                normalized = normalize_ip(src_ip)
                if normalized and not is_bogon_ip(normalized):
                    ips.add(normalized)

        if ips:
            log(f"Migrating {len(ips)} IPs from {iptables_bin} {CHAIN} to nftables", log_file)
            script = ""
            for ip in ips:
                script += f"add element inet {TABLE} {set_name} {{ {ip} }}\n"
            try:
                result = subprocess.run([NFT, "-f", "-"], input=script, capture_output=True, text=True)
                if result.returncode == 0:
                    migrated += len(ips)
                else:
                    log(f"nft migration error: {result.stderr}", log_file)
            except Exception as e:
                log(f"nft migration error: {e}", log_file)

            # Flush the old iptables chain after successful migration
            if migrated > 0:
                run_iptables([iptables_bin, "-F", CHAIN], log_file=log_file, fail_ok=True)
                log(f"Flushed old {iptables_bin} {CHAIN} chain", log_file)

    return migrated


def restore_ruleset(ruleset_path: Path, log_file: Optional[str] = None):
    """Restore a previously saved nftables ruleset (e.g. after reboot).

    Deletes the existing table first to guarantee idempotency — the saved
    file is a full table definition and nft -f uses 'add' semantics, so
    loading it on top of an existing table silently creates duplicate rules.
    """
    if not ruleset_path.exists():
        return
    try:
        # Drop the table if it exists so reload is always a clean slate.
        # The delete + reload from the file is atomic from nft's perspective.
        run_nft(["delete", "table", "inet", TABLE], fail_ok=True, log_file=log_file)
        result = subprocess.run(
            [NFT, "-f", str(ruleset_path)], capture_output=True, text=True,
        )
        if result.returncode == 0:
            log(f"Restored nftables ruleset from {ruleset_path}", log_file)
        else:
            log(f"Ruleset restore warning: {result.stderr}", log_file)
    except Exception as e:
        log(f"Ruleset restore error: {e}", log_file)


def deduplicate_nft_output(nft_output: str) -> str:
    """Remove duplicate rules from nft list table output.

    Tracks brace depth to identify chain blocks, then deduplicates
    rule lines (non-keyword lines that don't open/close braces).
    """
    lines = nft_output.splitlines()
    result = []
    depth = 0
    in_chain = False
    chain_depth = 0
    seen_rules: set = set()
    dupes = 0

    for line in lines:
        stripped = line.strip()
        opens = stripped.count("{")
        closes = stripped.count("}")
        depth_delta = opens - closes

        if in_chain and depth == chain_depth + 1:
            is_block_start = stripped.endswith("{")
            is_block_end = "}" in stripped
            is_keyword = any(stripped.startswith(kw) for kw in (
                "type ", "policy ", "hook ", "flags ", "devices ",
                "comment ", "timeout ", "gc-interval ", "size ",
                "elements",
            ))
            if not is_block_start and not is_block_end and not is_keyword:
                if stripped in seen_rules:
                    dupes += 1
                    depth += depth_delta
                    continue
                seen_rules.add(stripped)

        result.append(line)

        if not in_chain and stripped.startswith("chain ") and opens > 0:
            in_chain = True
            chain_depth = depth

        depth += depth_delta

        if in_chain and depth <= chain_depth:
            in_chain = False
            seen_rules.clear()

    if dupes:
        pass  # caller logs the count

    return "\n".join(result)


def save_ruleset(ruleset_path: Path, log_file: Optional[str] = None, dry_run: bool = False):
    """Save the current i2p_bans table to a file for boot persistence."""
    if dry_run:
        return
    try:
        result = subprocess.run(
            [NFT, "list", "table", "inet", TABLE],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            log(f"Ruleset save failed: {result.stderr}", log_file)
            return
        output = deduplicate_nft_output(result.stdout)
        ruleset_path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = str(ruleset_path) + ".tmp"
        with open(tmp_path, "w") as f:
            f.write(output)
        os.replace(tmp_path, ruleset_path)
        log(f"Saved nftables ruleset to {ruleset_path}", log_file)
        ensure_nftables_include(ruleset_path, log_file)
    except Exception as e:
        log(f"Ruleset save error: {e}", log_file)


def ensure_nftables_include(ruleset_path: Path, log_file: Optional[str] = None):
    """Ensure nftables.conf includes our ruleset file for boot loading."""
    conf = Path(NFTABLES_CONF)
    include_line = f'include "{ruleset_path}"'
    try:
        if conf.exists():
            content = conf.read_text()
            if str(ruleset_path) in content:
                return
        else:
            content = ""
        with open(conf, "a") as f:
            if content and not content.endswith("\n"):
                f.write("\n")
            f.write(f"{include_line}\n")
        log(f"Added include to {conf}", log_file)
    except Exception as e:
        log(f"Warning: could not update {conf}: {e}", log_file)


def remove_nftables_include(ruleset_path: Path, log_file: Optional[str] = None):
    """Remove our ruleset include from nftables.conf."""
    conf = Path(NFTABLES_CONF)
    if not conf.exists():
        return
    try:
        lines = conf.read_text().splitlines(keepends=True)
        filtered = [l for l in lines if str(ruleset_path) not in l]
        if len(filtered) != len(lines):
            with open(conf, "w") as f:
                f.writelines(filtered)
            log(f"Removed include from {conf}", log_file)
    except Exception as e:
        log(f"Warning: could not update {conf}: {e}", log_file)


def init_table_and_sets(ipv4_only: bool = False, log_file: Optional[str] = None):
    """Create nftables table, sets, and rules. Migrate from iptables on first run."""
    # Check if table already exists
    rc, _, _ = run_nft(["list", "table", "inet", TABLE], fail_ok=True)
    table_existed = (rc == 0)

    script = f"add table inet {TABLE}\n"
    script += f"add chain inet {TABLE} input {{ type filter hook input priority -10; policy accept; }}\n"
    script += f"add set inet {TABLE} {SET_IPV4} {{ type ipv4_addr; flags interval; }}\n"
    script += f"add rule inet {TABLE} input ip saddr @{SET_IPV4} drop\n"
    if not ipv4_only:
        script += f"add set inet {TABLE} {SET_IPV6} {{ type ipv6_addr; flags interval; }}\n"
        script += f"add rule inet {TABLE} input ip6 saddr @{SET_IPV6} drop\n"

    try:
        result = subprocess.run(
            [NFT, "-f", "-"], input=script, capture_output=True, text=True,
        )
        if result.returncode != 0:
            # "File exists" errors are fine for add operations
            if "File exists" not in result.stderr and "Device or resource busy" not in result.stderr:
                log(f"nft init error: {result.stderr}", log_file)
    except Exception as e:
        log(f"nft init error: {e}", log_file)

    # On first run, migrate existing iptables bans
    if not table_existed:
        # Check if old iptables chain exists
        rc_ipt, _, _ = run_iptables([IPTABLES, "-L", CHAIN, "-n"], fail_ok=True)
        if rc_ipt == 0:
            log("First run: migrating existing iptables bans to nftables", log_file)
            migrated = migrate_from_iptables(ipv4_only=ipv4_only, log_file=log_file)
            if migrated > 0:
                log(f"Migrated {migrated} bans from iptables to nftables", log_file)


def get_current_set_ips(ipv4_only: bool = False, log_file: Optional[str] = None) -> Tuple[Set[str], Set[str]]:
    """Read current IPs from nftables sets."""
    ipv4_ips: Set[str] = set()
    ipv6_ips: Set[str] = set()

    for set_name, ip_set, skip in [
        (SET_IPV4, ipv4_ips, False),
        (SET_IPV6, ipv6_ips, ipv4_only),
    ]:
        if skip:
            continue
        rc, stdout, _ = run_nft(["list", "set", "inet", TABLE, set_name], fail_ok=True)
        if rc != 0:
            continue
        in_elements = False
        for line in stdout.splitlines():
            line = line.strip()
            if "elements = " in line:
                # Single-line: elements = { 1.2.3.4, 5.6.7.8 }
                if "{" in line and "}" in line:
                    block = line[line.index("{") + 1 : line.index("}")].strip()
                    if block:
                        for entry in block.split(","):
                            entry = entry.strip()
                            ip_str = entry.split("/")[0] if "/" in entry else entry
                            normalized = normalize_ip(ip_str)
                            if normalized:
                                ip_set.add(normalized)
                else:
                    in_elements = True
                continue
            if in_elements:
                if "}" in line:
                    line = line[: line.index("}")].strip()
                    in_elements = False
                if line:
                    for entry in line.rstrip(",").split(","):
                        entry = entry.strip()
                        if not entry:
                            continue
                        ip_str = entry.split("/")[0] if "/" in entry else entry
                        normalized = normalize_ip(ip_str)
                        if normalized:
                            ip_set.add(normalized)

    return ipv4_ips, ipv6_ips


def batch_update_set(
    set_name: str,
    to_add: Set[str],
    to_remove: Set[str],
    log_file: Optional[str] = None,
) -> Tuple[int, int]:
    if not to_add and not to_remove:
        return 0, 0

    script = ""
    for ip in to_add:
        script += f"add element inet {TABLE} {set_name} {{ {ip} }}\n"
    for ip in to_remove:
        script += f"delete element inet {TABLE} {set_name} {{ {ip} }}\n"

    try:
        result = subprocess.run(
            [NFT, "-f", "-"], input=script, capture_output=True, text=True,
        )
        if result.returncode != 0:
            log(f"nft batch update error: {result.stderr}", log_file)
            return 0, 0
        return len(to_add), len(to_remove)
    except Exception as e:
        log(f"nft batch update error: {e}", log_file)
        return 0, 0


def flush_set(set_name: str, log_file: Optional[str] = None):
    run_nft(["flush", "set", "inet", TABLE, set_name], log_file=log_file, fail_ok=True)


def process_bans(
    categories: Dict[str, Set[str]],
    tracking_file: Path,
    ban_duration: int,
    ipv4_only: bool = False,
    log_file: Optional[str] = None,
    dry_run: bool = False,
) -> Tuple[int, int, int, int, int]:
    now = int(time.time())
    all_ips: Set[str] = set()
    bogon_filtered = 0

    for ips in categories.values():
        for ip in ips:
            if is_bogon_ip(ip):
                bogon_filtered += 1
                log(f"Filtered bogon IP: {ip}", log_file)
            else:
                all_ips.add(ip)

    wanted_v4: Set[str] = {ip for ip in all_ips if is_ipv4(ip)}
    wanted_v6: Set[str] = {ip for ip in all_ips if is_ipv6(ip)} if not ipv4_only else set()

    tracking = load_tracking(tracking_file)
    current_v4, current_v6 = get_current_set_ips(ipv4_only=ipv4_only, log_file=log_file)

    to_add_v4 = wanted_v4 - current_v4
    expired_v4 = set()
    for ip in current_v4 - wanted_v4:
        if ip in tracking:
            if now - tracking[ip] >= ban_duration:
                expired_v4.add(ip)
        else:
            expired_v4.add(ip)

    to_add_v6 = wanted_v6 - current_v6
    expired_v6 = set()
    for ip in current_v6 - wanted_v6:
        if ip in tracking:
            if now - tracking[ip] >= ban_duration:
                expired_v6.add(ip)
        else:
            expired_v6.add(ip)

    if not dry_run:
        batch_update_set(SET_IPV4, to_add_v4, expired_v4, log_file=log_file)
        if not ipv4_only:
            batch_update_set(SET_IPV6, to_add_v6, expired_v6, log_file=log_file)

    new_bans = len(to_add_v4) + len(to_add_v6)
    expired_count = len(expired_v4) + len(expired_v6)

    valid_tracking: Dict[str, int] = {}
    refreshed = 0
    for ip in all_ips:
        if ip in tracking:
            refreshed += 1
        valid_tracking[ip] = now

    for ip, timestamp in tracking.items():
        if ip not in valid_tracking:
            if now - timestamp < ban_duration:
                valid_tracking[ip] = timestamp

    if not dry_run:
        save_tracking(valid_tracking, tracking_file)

    return new_bans, refreshed, expired_count, len(valid_tracking), bogon_filtered


def clean_all_bans(
    tracking_file: Path,
    ruleset_path: Path,
    ipv4_only: bool = True,
    log_file: Optional[str] = None,
    dry_run: bool = False,
):
    log("Cleaning all bans...", log_file)
    if not dry_run:
        run_nft(["delete", "table", "inet", TABLE], fail_ok=True, log_file=log_file)

        # Clean up legacy iptables chain if present
        for iptables_bin in [IPTABLES] + ([IP6TABLES] if not ipv4_only else []):
            rc, _, _ = run_iptables([iptables_bin, "-L", CHAIN, "-n"], fail_ok=True)
            if rc == 0:
                run_iptables([iptables_bin, "-D", "INPUT", "-j", CHAIN], fail_ok=True, log_file=log_file)
                run_iptables([iptables_bin, "-F", CHAIN], fail_ok=True, log_file=log_file)
                run_iptables([iptables_bin, "-X", CHAIN], fail_ok=True, log_file=log_file)
                log(f"Removed legacy iptables chain {CHAIN} from {iptables_bin}", log_file)
    if tracking_file.exists() and not dry_run:
        tracking_file.unlink()
    if ruleset_path.exists() and not dry_run:
        try:
            ruleset_path.unlink()
            log(f"Removed ruleset file {ruleset_path}", log_file)
        except OSError as e:
            log(f"Error removing ruleset file: {e}", log_file)
    remove_nftables_include(ruleset_path, log_file)
    log("Cleared sets and tracking file", log_file)


def reset_nftables(ruleset_path: Path, log_file: Optional[str] = None):
    """Nuke and reload: delete the table, remove the saved ruleset, restart nftables."""
    log("Resetting nftables...", log_file)
    run_nft(["delete", "table", "inet", TABLE], fail_ok=True, log_file=log_file)
    if ruleset_path.exists():
        try:
            ruleset_path.unlink()
            log(f"Removed ruleset file {ruleset_path}", log_file)
        except OSError as e:
            log(f"Error removing ruleset file: {e}", log_file)
    try:
        result = subprocess.run(
            ["systemctl", "restart", "nftables"], capture_output=True, text=True,
        )
        if result.returncode == 0:
            log("nftables service restarted", log_file)
        else:
            log(f"nftables restart warning: {result.stderr}", log_file)
    except Exception as e:
        log(f"nftables restart error: {e}", log_file)
    log("Reset complete. Run the script normally to rebuild bans.", log_file)


def list_current_bans(
    tracking_file: Path,
    ipv6_enabled: bool = True,
):
    print(f"Current nftables bans in table '{TABLE}':")
    print("-" * 72)

    tracking = load_tracking(tracking_file)
    now = int(time.time())
    total = 0

    current_v4, current_v6 = get_current_set_ips(ipv4_only=not ipv6_enabled)

    for ips, proto in [(current_v4, "IPv4")] + ([(current_v6, "IPv6")] if ipv6_enabled else []):
        if not ips:
            print(f"\n[{proto}] No bans")
            continue

        print(f"\n[{proto}] ({len(ips)} IPs)")
        print(f"{'#':<5} {'Source IP':<45} {'Age'}")
        print("-" * 72)

        for idx, ip in enumerate(sorted(ips), 1):
            age_str = ""
            if ip in tracking:
                age = now - tracking[ip]
                if age < 3600:
                    age_str = f"{age // 60}m"
                elif age < 86400:
                    age_str = f"{age // 3600}h"
                else:
                    age_str = f"{age // 86400}d"
            print(f"{idx:<5} {ip:<45} {age_str}")
            total += 1

    if total == 0:
        print("\nNo active bans")
    else:
        print(f"\nTotal: {total} bans")


def list_ban_summary(ipv6_enabled: bool = True):
    current_v4, current_v6 = get_current_set_ips(ipv4_only=not ipv6_enabled)
    print(f"IPv4: {len(current_v4)}")
    if ipv6_enabled:
        print(f"IPv6: {len(current_v6)}")


def check_lock(lock_file: Path) -> bool:
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
    lock_file.parent.mkdir(parents=True, exist_ok=True)
    try:
        fd = os.open(str(lock_file), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
        try:
            os.write(fd, str(os.getpid()).encode())
        finally:
            os.close(fd)
        return True
    except FileExistsError:
        if check_lock(lock_file):
            return False
        try:
            os.unlink(str(lock_file))
        except OSError:
            return False
        try:
            fd = os.open(str(lock_file), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
            try:
                os.write(fd, str(os.getpid()).encode())
            finally:
                os.close(fd)
            return True
        except OSError:
            return False
    except OSError:
        return False


def release_lock(lock_file: Path):
    try:
        if lock_file.exists():
            lock_file.unlink()
    except OSError:
        pass


def main():
    parser = argparse.ArgumentParser(description="I2P Session Ban to nftables")
    parser.add_argument(
        "-l", "--log", help=f"Log file path (default: {DEFAULT_LOG_FILE})"
    )
    parser.add_argument("--clean", action="store_true", help="Clean all bans and exit")
    parser.add_argument("--reset", action="store_true", help="Delete table, remove ruleset, restart nftables service")
    parser.add_argument("--list", action="store_true", help="List all current bans and exit")
    parser.add_argument("--list-summary", action="store_true", help="Print ban counts for IPv4/IPv6 and exit")
    parser.add_argument(
        "--workers", type=int, default=NUM_WORKERS,
        help=f"Number of parallel workers (default: {NUM_WORKERS})",
    )
    parser.add_argument(
        "--ipv4-only", action="store_true",
        help="Only process IPv4 addresses, ignore IPv6",
    )
    parser.add_argument(
        "--ban-dir", type=Path, default=BAN_DIR,
        help=f"Ban directory (default: {BAN_DIR})",
    )
    parser.add_argument(
        "--tracking-file", type=Path, default=BAN_TRACKING,
        help=f"Tracking file (default: {BAN_TRACKING})",
    )
    parser.add_argument(
        "--lock-file", type=Path, default=LOCK_FILE,
        help=f"Lock file (default: {LOCK_FILE})",
    )
    parser.add_argument(
        "--ban-duration", type=str, default="7d",
        help="Ban duration (e.g. 7d, 168h, 1w, forever, or seconds). Default: 7d",
    )
    parser.add_argument(
        "--window-hours", type=int, default=BAN_WINDOW_HOURS,
        help=f"Time window for ban files in hours (default: {BAN_WINDOW_HOURS})",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Simulate changes without applying rules",
    )
    parser.add_argument(
        "--ruleset-file", type=Path, default=Path(DEFAULT_RULESET_FILE),
        help=f"Nftables ruleset save path for boot persistence (default: {DEFAULT_RULESET_FILE})",
    )
    args = parser.parse_args()

    ban_dir = args.ban_dir
    ban_file = ban_dir / "sessionbans.txt"
    tracking_file = args.tracking_file
    lock_file = args.lock_file
    ban_duration = parse_duration_seconds(args.ban_duration)
    if ban_duration <= 0:
        print(f"ERROR: Invalid ban duration: {args.ban_duration}", file=sys.stderr)
        sys.exit(1)
    window_hours = args.window_hours
    workers = args.workers
    ipv4_only = args.ipv4_only
    dry_run = args.dry_run
    ruleset_file = args.ruleset_file
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

    if args.list:
        list_current_bans(tracking_file, ipv6_enabled=not ipv4_only)
        return

    if args.list_summary:
        list_ban_summary(ipv6_enabled=not ipv4_only)
        return

    if not acquire_lock(lock_file):
        log(f"ERROR: Another instance is already running or stale lock at {lock_file}", args.log)
        sys.exit(1)

    try:
        if args.clean:
            clean_all_bans(tracking_file, ruleset_file, ipv4_only=ipv4_only, log_file=log_file, dry_run=dry_run)
            return

        if args.reset:
            reset_nftables(ruleset_file, log_file=log_file)
            return

        if dry_run:
            log("DRY RUN MODE - No changes will be applied", log_file)

        log("Starting I2P session ban sync", log_file)

        if not dry_run:
            restore_ruleset(ruleset_file, log_file=log_file)
            # Only init if table doesn't exist after restore.
            # restore_ruleset loads the full table including rules, so running
            # init_table_and_sets unconditionally adds duplicate drop rules each run.
            rc, _, _ = run_nft(["list", "table", "inet", TABLE], fail_ok=True)
            if rc != 0:
                init_table_and_sets(ipv4_only=ipv4_only, log_file=log_file)
            else:
                log("Table already exists after restore, skipping init", log_file)

        if not ban_file.exists():
            log(f"ERROR: Ban file not found: {ban_file}", log_file)
            sys.exit(1)

        log(f"Extracting IPs from sessionban files (window: {window_hours}h)...", log_file)
        categories = extract_all_categories(
            ban_dir, ban_file, workers=workers, ipv4_only=ipv4_only, window_hours=window_hours
        )

        long_count = len(categories.get("long", set()))
        xg_count = len(categories.get("xg", set()))
        lu_count = len(categories.get("lu", set()))
        old_slow_count = len(categories.get("old_slow", set()))
        blocklist_count = len(categories.get("blocklist", set()))
        sybil_count = len(categories.get("sybil", set()))
        no_version_count = len(categories.get("no_version", set()))
        bad_handshake_count = len(categories.get("bad_handshake", set()))

        log(
            f"Long-term bans (>=4h): {long_count}, "
            f"XG Router: {xg_count}, "
            f"LU Router: {lu_count}, "
            f"Old and Slow: {old_slow_count}, "
            f"Blocklist: {blocklist_count}, "
            f"Sybil: {sybil_count}, "
            f"No version: {no_version_count}, "
            f"Bad Handshake: {bad_handshake_count}",
            log_file,
        )

        new_bans, refreshed, expired, total, bogon_filtered = process_bans(
            categories, tracking_file, ban_duration,
            ipv4_only=ipv4_only, log_file=log_file, dry_run=dry_run,
        )

        tracking = load_tracking(tracking_file)
        cleaned_bogons = 0
        bogons_v4: Set[str] = set()
        bogons_v6: Set[str] = set()
        for ip in list(tracking.keys()):
            if is_bogon_ip(ip):
                if is_ipv4(ip):
                    bogons_v4.add(ip)
                else:
                    bogons_v6.add(ip)
                del tracking[ip]
                cleaned_bogons += 1
                log(f"Removed existing bogon from tracking: {ip}", log_file)

        if cleaned_bogons > 0 and not dry_run:
            batch_update_set(SET_IPV4, set(), bogons_v4, log_file)
            if not ipv4_only:
                batch_update_set(SET_IPV6, set(), bogons_v6, log_file)
            save_tracking(tracking, tracking_file)

        if new_bans > 0:
            log(f"Added {new_bans} new bans", log_file)
        if expired > 0:
            log(f"Removed {expired} expired bans", log_file)
        if bogon_filtered > 0:
            log(f"Filtered {bogon_filtered} bogon IPs from new bans", log_file)
        if cleaned_bogons > 0:
            log(f"Cleaned {cleaned_bogons} existing bogon IPs from tracking", log_file)

        if not dry_run:
            save_ruleset(ruleset_file, log_file=log_file)

        log(f"Total active bans: {total}", log_file)
        log("Done", log_file)

    finally:
        release_lock(lock_file)


if __name__ == "__main__":
    main()
