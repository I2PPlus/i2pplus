#!/usr/bin/env python3
"""
I2P+ Router Memory Leak Detection Script

This script helps identify memory leaks in the I2P+ Java router by:
1. Monitoring JMX memory metrics
2. Parsing GC logs for patterns indicating leaks
3. Taking and analyzing heap dumps
4. Tracking memory growth over time
"""

import argparse
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Optional


@dataclass
class MemorySnapshot:
    timestamp: datetime
    heap_used_mb: float
    heap_max_mb: float
    heap_committed_mb: float
    non_heap_used_mb: float
    gc_count: int
    gc_time_ms: int
    young_gc_count: int
    old_gc_count: int


def find_java_process() -> Optional[int]:
    """Find the I2P+ router Java process PID."""
    try:
        result = subprocess.run(
            ["pgrep", "-f", "i2p router"],
            capture_output=True,
            text=True,
            timeout=10
        )
        if result.returncode == 0 and result.stdout.strip():
            pids = result.stdout.strip().split('\n')
            for p in pids:
                try:
                    pid = int(p.strip())
                    with open(f'/proc/{pid}/cmdline', 'r') as f:
                        cmdline = f.read()
                        if 'i2p' in cmdline.lower() or 'router' in cmdline.lower():
                            return pid
                except:
                    pass
        
        result = subprocess.run(
            ["pgrep", "-f", "org\.i2p\.router\.Router"],
            capture_output=True,
            text=True,
            timeout=10
        )
        if result.returncode == 0 and result.stdout.strip():
            pids = result.stdout.strip().split('\n')
            return int(pids[0])
            
    except Exception as e:
        print(f"Error finding Java process: {e}")
    return None


def get_memory_from_proc(pid: int) -> Optional[dict]:
    """Get memory usage from /proc filesystem (Linux only)."""
    try:
        status_path = f'/proc/{pid}/status'
        with open(status_path, 'r') as f:
            content = f.read()
        
        mem_info = {}
        for line in content.split('\n'):
            if line.startswith('VmRSS:'):
                mem_info['rss_kb'] = int(line.split()[1])
            elif line.startswith('VmSize:'):
                mem_info['vms_kb'] = int(line.split()[1])
            elif line.startswith('VmData:'):
                mem_info['data_kb'] = int(line.split()[1])
            elif line.startswith('VmStk:'):
                mem_info['stack_kb'] = int(line.split()[1])
            elif line.startswith('VmPeak:'):
                mem_info['peak_kb'] = int(line.split()[1])
                
        return mem_info
    except Exception as e:
        return None


def get_jstat_memory(pid: int) -> Optional[MemorySnapshot]:
    """Get memory statistics using jstat."""
    try:
        result = subprocess.run(
            ["jstat", "-gc", str(pid)],
            capture_output=True,
            text=True,
            timeout=10
        )
        if result.returncode != 0:
            return None
            
        lines = result.stdout.strip().split('\n')
        if len(lines) < 2:
            return None
            
        headers = lines[0].split()
        values = lines[1].split()
        
        gc_data = dict(zip(headers, values))
        
        s0c = float(gc_data.get('S0C', 0))
        s1c = float(gc_data.get('S1C', 0))
        ec = float(gc_data.get('EC', 0))
        oc = float(gc_data.get('OC', 0))
        pc = float(gc_data.get('PC', 0))
        
        s0u = float(gc_data.get('S0U', 0))
        s1u = float(gc_data.get('S1U', 0))
        eu = float(gc_data.get('EU', 0))
        ou = float(gc_data.get('OU', 0))
        pu = float(gc_data.get('PU', 0))
        
        young_gc = int(gc_data.get('YGCT', 0))
        old_gc = int(gc_data.get('OGCT', 0))
        
        heap_used = (s0u + s1u + eu + ou) / 1024
        heap_max = (s0c + s1c + ec + oc) / 1024
        heap_committed = (s0c + s1c + ec + oc) / 1024
        non_heap_used = pu / 1024
        
        return MemorySnapshot(
            timestamp=datetime.now(),
            heap_used_mb=heap_used,
            heap_max_mb=heap_max,
            heap_committed_mb=heap_committed,
            non_heap_used_mb=non_heap_used,
            gc_count=young_gc + old_gc,
            gc_time_ms=int(gc_data.get('GCT', 0) * 1000),
            young_gc_count=young_gc,
            old_gc_count=old_gc
        )
    except Exception as e:
        print(f"Error getting jstat data: {e}")
        return None


def get_jmap_heap(pid: int) -> Optional[dict]:
    """Get heap summary using jmap."""
    try:
        result = subprocess.run(
            ["jmap", "-heap", str(pid)],
            capture_output=True,
            text=True,
            timeout=30
        )
        if result.returncode != 0:
            return None
            
        heap_info = {}
        for line in result.stdout.split('\n'):
            if 'Eden Space' in line:
                match = re.search(r'used (\d+)K', line)
                if match:
                    heap_info['eden_used_kb'] = int(match.group(1))
            elif 'Survivor Space' in line:
                match = re.search(r'used (\d+)K', line)
                if match:
                    heap_info['survivor_used_kb'] = int(match.group(1))
            elif 'Tenured Gen' in line or 'Old Gen' in line:
                match = re.search(r'used (\d+)K', line)
                if match:
                    heap_info['tenured_used_kb'] = int(match.group(1))
            elif 'Metaspace' in line:
                match = re.search(r'used (\d+)K', line)
                if match:
                    heap_info['metaspace_used_kb'] = int(match.group(1))
                    
        return heap_info
    except Exception as e:
        print(f"Error getting jmap heap: {e}")
        return None


def parse_gc_log(log_path: str) -> dict:
    """Parse GC logs to identify potential memory leak patterns."""
    if not os.path.exists(log_path):
        return {}
        
    patterns = {
        'full_gc_count': 0,
        'concurrent_failure': 0,
        'promotion_failure': 0,
        'allocation_failure': 0,
        'gc_times': [],
        'heap_sizes': []
    }
    
    try:
        with open(log_path, 'r') as f:
            for line in f:
                if 'Full GC' in line or 'Full GC' in line:
                    patterns['full_gc_count'] += 1
                if 'concurrent mode failure' in line.lower():
                    patterns['concurrent_failure'] += 1
                if 'promotion failure' in line.lower():
                    patterns['promotion_failure'] += 1
                if 'allocation failure' in line.lower():
                    patterns['allocation_failure'] += 1
                    
                time_match = re.search(r'\[(\d+\.\d+)\]', line)
                if time_match:
                    patterns['gc_times'].append(float(time_match.group(1)))
                    
                heap_match = re.search(r'->(\d+)K->(\d+)K\((\d+)K\)', line)
                if heap_match:
                    patterns['heap_sizes'].append({
                        'before': int(heap_match.group(1)),
                        'after': int(heap_match.group(2)),
                        'max': int(heap_match.group(3))
                    })
                    
    except Exception as e:
        print(f"Error parsing GC log: {e}")
        
    return patterns


def analyze_heap_dump(pid: int, output_dir: str) -> Optional[str]:
    """Generate and analyze a heap dump."""
    heap_file = os.path.join(output_dir, f"heap_dump_{int(time.time())}.hprof")
    try:
        print(f"Generating heap dump to {heap_file}...")
        result = subprocess.run(
            ["jmap", "-dump:format=b,file=" + heap_file, str(pid)],
            capture_output=True,
            text=True,
            timeout=120
        )
        if result.returncode == 0:
            return heap_file
    except Exception as e:
        print(f"Error generating heap dump: {e}")
    return None


def monitor_memory(pid: int, interval: int, count: int, output_file: str):
    """Monitor memory usage over time."""
    snapshots = []
    use_proc = False
    
    first_snapshot = get_jstat_memory(pid)
    if first_snapshot is None:
        print("jstat unavailable - using /proc fallback (RSS memory only)")
        use_proc = True
    
    if use_proc:
        print(f"Monitoring PID {pid} every {interval}s for {count} iterations (RSS only)...")
        print(f"{'Time':<12} {'RSS Memory':<15} {'VMS Memory':<15}")
        print("-" * 55)
    else:
        print(f"Monitoring PID {pid} every {interval}s for {count} iterations...")
        print(f"{'Time':<12} {'Heap Used':<12} {'Heap Max':<12} {'Heap %':<10} {'GC Count':<10} {'GC Time':<10}")
        print("-" * 70)
    
    for i in range(count):
        timestamp = datetime.now().strftime("%H:%M:%S")
        
        if use_proc:
            mem_info = get_memory_from_proc(pid)
            if mem_info:
                rss_mb = mem_info.get('rss_kb', 0) / 1024
                vms_mb = mem_info.get('vms_kb', 0) / 1024
                print(f"{timestamp:<12} {rss_mb:>12.1f} MB     {vms_mb:>12.1f} MB")
                
                snapshots.append({'rss': rss_mb, 'vms': vms_mb})
                
                if output_file:
                    with open(output_file, 'a') as f:
                        f.write(f"{datetime.now().isoformat()},{rss_mb},{vms_mb}\n")
        else:
            snapshot = get_jstat_memory(pid)
            if snapshot:
                snapshots.append(snapshot)
                heap_pct = (snapshot.heap_used_mb / snapshot.heap_max_mb * 100) if snapshot.heap_max_mb > 0 else 0
                
                print(f"{timestamp:<12} {snapshot.heap_used_mb:>8.1f} MB {snapshot.heap_max_mb:>8.1f} MB "
                      f"{heap_pct:>6.1f}%   {snapshot.gc_count:<10} {snapshot.gc_time_ms:>8}ms")
                
                if output_file:
                    with open(output_file, 'a') as f:
                        f.write(f"{snapshot.timestamp.isoformat()},{snapshot.heap_used_mb},"
                               f"{snapshot.heap_max_mb},{snapshot.heap_committed_mb},"
                               f"{snapshot.non_heap_used_mb},{snapshot.gc_count},"
                               f"{snapshot.gc_time_ms},{snapshot.young_gc_count},{snapshot.old_gc_count}\n")
        
        if i < count - 1:
            time.sleep(interval)
    
    if len(snapshots) >= 2:
        analyze_trend(snapshots, use_proc)


def analyze_trend(snapshots: list, use_proc: bool = False):
    """Analyze memory trend to detect potential leaks."""
    if len(snapshots) < 2:
        return
    
    if use_proc:
        first = snapshots[0]
        last = snapshots[-1]
        
        rss_growth = last.get('rss', 0) - first.get('rss', 0)
        rss_growth_pct = (rss_growth / first.get('rss', 1)) * 100 if first.get('rss', 0) > 0 else 0
        
        print("\n" + "=" * 70)
        print("MEMORY LEAK ANALYSIS (/proc fallback)")
        print("=" * 70)
        print(f"RSS growth:     {rss_growth:>8.1f} MB ({rss_growth_pct:>+.1f}%)")
        
        if rss_growth_pct > 20:
            print("\n[WARNING] Significant RSS memory growth detected - possible memory leak!")
        elif rss_growth_pct > 10:
            print("\n[CAUTION] Moderate RSS memory growth detected - monitor closely")
        else:
            print("\n[OK] Memory usage appears stable")
    else:
        first = snapshots[0]
        last = snapshots[-1]
        
        heap_growth = last.heap_used_mb - first.heap_used_mb
        heap_growth_pct = (heap_growth / first.heap_used_mb * 100) if first.heap_used_mb > 0 else 0
        
        gc_increase = last.gc_count - first.gc_count
        gc_time_increase = last.gc_time_ms - first.gc_time_ms
        
        print("\n" + "=" * 70)
        print("MEMORY LEAK ANALYSIS")
        print("=" * 70)
        print(f"Heap growth:     {heap_growth:>8.1f} MB ({heap_growth_pct:>+.1f}%)")
        print(f"GC count:       {gc_increase:>8} total GCs")
        print(f"GC time:        {gc_time_increase:>8} ms")
        
        if heap_growth_pct > 20:
            print("\n[WARNING] Significant heap growth detected - possible memory leak!")
        elif heap_growth_pct > 10:
            print("\n[CAUTION] Moderate heap growth detected - monitor closely")
        else:
            print("\n[OK] Heap usage appears stable")
            
        if last.old_gc_count > first.old_gc_count:
            old_gc_increase = last.old_gc_count - first.old_gc_count
            print(f"[WARNING] {old_gc_increase} old-generation GCs detected - heap may be filling up")


def find_gc_logs() -> list:
    """Find GC log files in common locations."""
    gc_log_paths = []
    
    home = os.path.expanduser("~")
    search_paths = [
        os.path.join(home, "i2p", "logs"),
        os.path.join(home, ".i2p", "logs"),
        "/var/log/i2p/logs",
        "./logs"
    ]
    
    for path in search_paths:
        if os.path.exists(path):
            for f in os.listdir(path):
                if 'gc' in f.lower() or 'garbage' in f.lower():
                    gc_log_paths.append(os.path.join(path, f))
                    
    return gc_log_paths


def analyze_wrapper_log() -> Optional[dict]:
    """Analyze I2P wrapper log for memory-related entries."""
    home = os.path.expanduser("~")
    log_paths = [
        os.path.join(home, "i2p", "wrapper.log"),
        os.path.join(home, ".i2p", "wrapper.log"),
        "/var/log/i2p/wrapper.log"
    ]
    
    for log_path in log_paths:
        if os.path.exists(log_path):
            try:
                with open(log_path, 'r') as f:
                    content = f.read()
                
                info = {'log_path': log_path}
                
                mem_warnings = []
                oom_events = []
                for line in content.split('\n'):
                    if 'OutOfMemory' in line or 'java.lang.OutOfMemoryError' in line:
                        oom_events.append(line.strip()[:100])
                    if 'memory' in line.lower() and ('warning' in line.lower() or 'error' in line.lower()):
                        mem_warnings.append(line.strip()[:100])
                
                if mem_warnings or oom_events:
                    info['warnings'] = mem_warnings[-5:]
                    info['oom_events'] = oom_events[-5:]
                    
                return info
            except Exception as e:
                pass
    return None


def diagnose_memory_issues(console_url: str = "http://localhost:7657") -> dict:
    """Diagnose memory issues from I2P console."""
    import urllib.request
    import re
    
    diagnosis = {}
    
    try:
        url = f"{console_url}/home"
        req = urllib.request.Request(url, headers={'User-Agent': 'I2P Memory Monitor'})
        with urllib.request.urlopen(req, timeout=5) as response:
            html = response.read().decode('utf-8')
            
            mem_match = re.search(r'RAM:\s*([\d,]+)\s*/\s*([\d,]+)\s*M', html)
            if mem_match:
                diagnosis['ram_used_mb'] = int(mem_match.group(1).replace(',', ''))
                diagnosis['ram_max_mb'] = int(mem_match.group(2).replace(',', ''))
                if diagnosis['ram_max_mb'] > 0:
                    diagnosis['ram_percent'] = int(diagnosis['ram_used_mb'] / diagnosis['ram_max_mb'] * 100)
    except Exception as e:
        diagnosis['error'] = str(e)
    
    try:
        url = f"{console_url}/tunnels"
        req = urllib.request.Request(url, headers={'User-Agent': 'I2P Memory Monitor'})
        with urllib.request.urlopen(req, timeout=5) as response:
            html = response.read().decode('utf-8')
            
            transit_match = re.search(r'Transit.*?(\d+)\s*/\s*(\d+)', html)
            if transit_match:
                diagnosis['transit_current'] = int(transit_match.group(1))
                diagnosis['transit_max'] = int(transit_match.group(2))
                
            service_match = re.search(r'Service.*?(\d+)\s*/\s*(\d+)', html)
            if service_match:
                diagnosis['service_tunnels'] = int(service_match.group(1))
                
            floodfill_match = re.search(r'Floodfill.*?(\d+)', html)
            if floodfill_match:
                diagnosis['floodfill_peers'] = int(floodfill_match.group(1))
    except Exception as e:
        pass
    
    print("\n" + "=" * 70)
    print("MEMORY DIAGNOSIS")
    print("=" * 70)
    
    if 'error' in diagnosis:
        print(f"Error: {diagnosis['error']}")
        return diagnosis
        
    print(f"RAM Usage:      {diagnosis.get('ram_used_mb', 'N/A')} / {diagnosis.get('ram_max_mb', 'N/A')} MB ({diagnosis.get('ram_percent', 0)}%)")
    
    if 'transit_current' in diagnosis:
        print(f"Transit Tunnels: {diagnosis['transit_current']} / {diagnosis['transit_max']}")
        if diagnosis['transit_current'] > 5000:
            print("[CRITICAL] Transit tunnels extremely high! This is likely the memory issue.")
            print("           Recommend: Reduce transit.maxTunnels to 2000 or less")
        elif diagnosis['transit_current'] > 2000:
            print("[WARNING] Transit tunnels very high!")
            
    if diagnosis.get('floodfill_peers', 0) > 1000:
        print(f"Floodfill Peers: {diagnosis['floodfill_peers']} (high floodfill load)")
        
    if diagnosis.get('ram_percent', 0) > 80:
        print("\n[CRITICAL] Heap usage above 80%!")
        print(" Recommendations:")
        print("  1. Reduce transit.maxTunnels (currently " + str(diagnosis.get('transit_max', '?')) + ")")
        print("  2. Disable floodfill if not needed")
        print("  3. Reduce router console memory display refresh rate")
        print("  4. Consider reducing bandwidth limits to lower peer count")
    
    return diagnosis


def get_memory_from_console(port: int = 7657) -> Optional[dict]:
    """Try to get memory info from I2P console API."""
    try:
        import urllib.request
        url = f"http://localhost:{port}/router.jsp"
        req = urllib.request.Request(url, headers={'User-Agent': 'I2P Memory Monitor'})
        with urllib.request.urlopen(req, timeout=5) as response:
            html = response.read().decode('utf-8')
            
            mem_data = {}
            import re
            match = re.search(r'Java Memory.*?(\d+)\s*MB.*?of\s*(\d+)\s*MB', html, re.DOTALL)
            if match:
                mem_data['used_mb'] = int(match.group(1))
                mem_data['max_mb'] = int(match.group(2))
                
            return mem_data
    except Exception:
        return None


def main():
    parser = argparse.ArgumentParser(
        description="I2P+ Router Memory Leak Detection Tool",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s --monitor              Monitor memory in real-time
  %(prog)s --pid 12345            Monitor specific PID
  %(prog)s --gc-logs              Analyze GC logs
  %(prog)s --heap-dump            Take and analyze heap dump
  %(prog)s --status               Get quick status from /proc (no JMX needed)
        """
    )
    parser.add_argument('--pid', type=int, help='Java process PID')
    parser.add_argument('--monitor', action='store_true', help='Monitor memory usage')
    parser.add_argument('--interval', type=int, default=10, help='Monitoring interval in seconds')
    parser.add_argument('--count', type=int, default=10, help='Number of monitoring iterations')
    parser.add_argument('--gc-logs', action='store_true', help='Analyze GC logs')
    parser.add_argument('--heap-dump', action='store_true', help='Generate and analyze heap dump')
    parser.add_argument('--output', type=str, help='Output file for monitoring data')
    parser.add_argument('--status', action='store_true', help='Get status from /proc (no JVM tools needed)')
    parser.add_argument('--diagnose', action='store_true', help='Diagnose memory issues from I2P console')
    parser.add_argument('--console-url', default='http://localhost:7657', help='I2P console URL')
    
    args = parser.parse_args()
    
    pid = args.pid
    
    if args.diagnose:
        diagnose_memory_issues(args.console_url)
        sys.exit(0)
    
    if not pid:
        pid = find_java_process()
        if not pid:
            print("Error: Could not find I2P+ router process. Specify with --pid")
            sys.exit(1)
    
    print(f"Using PID: {pid}")
    
    if args.status:
        mem_info = get_memory_from_proc(pid)
        if mem_info:
            print(f"\nMemory Usage (from /proc):")
            print(f"  RSS (Physical): {mem_info.get('rss_kb', 0)/1024:.1f} MB")
            print(f"  VMS (Virtual):  {mem_info.get('vms_kb', 0)/1024:.1f} MB")
            print(f"  Data:           {mem_info.get('data_kb', 0)/1024:.1f} MB")
            print(f"  Peak:           {mem_info.get('peak_kb', 0)/1024:.1f} MB")
        else:
            print("Could not read /proc memory info")
        
        wrapper_info = analyze_wrapper_log()
        if wrapper_info:
            print(f"\nWrapper Log Analysis ({wrapper_info.get('log_path', 'unknown')}):")
            if wrapper_info.get('warnings'):
                print("  Memory Warnings:")
                for w in wrapper_info['warnings']:
                    print(f"    - {w}")
            if wrapper_info.get('oom_events'):
                print("  OOM Events:")
                for o in wrapper_info['oom_events']:
                    print(f"    - {o}")
            if not wrapper_info.get('warnings') and not wrapper_info.get('oom_events'):
                print("  No memory warnings found")
        
        console_mem = get_memory_from_console()
        if console_mem:
            print(f"\nI2P Console Memory:")
            print(f"  Used: {console_mem.get('used_mb', 'N/A')} MB")
            print(f"  Max:  {console_mem.get('max_mb', 'N/A')} MB")
        
        if args.diagnose:
            diagnose_memory_issues(args.console_url)
        
        sys.exit(0)
    
    if args.monitor:
        if args.output:
            with open(args.output, 'w') as f:
                f.write("timestamp,heap_used_mb,heap_max_mb,heap_committed_mb,non_heap_used_mb,gc_count,gc_time_ms,young_gc_count,old_gc_count\n")
        monitor_memory(pid, args.interval, args.count, args.output)
    
    if args.gc_logs:
        gc_files = find_gc_logs()
        if gc_files:
            print("\nAnalyzing GC logs...")
            for log_file in gc_files:
                print(f"\nLog: {log_file}")
                patterns = parse_gc_log(log_file)
                if patterns:
                    print(f"  Full GC count: {patterns['full_gc_count']}")
                    print(f"  Concurrent failures: {patterns['concurrent_failure']}")
                    print(f"  Promotion failures: {patterns['promotion_failure']}")
                    if patterns['full_gc_count'] > 10:
                        print("  [WARNING] High number of Full GCs - possible memory pressure!")
        else:
            print("No GC logs found")
    
    if args.heap_dump:
        output_dir = os.path.expanduser("~/i2p/heap_dumps")
        os.makedirs(output_dir, exist_ok=True)
        dump_file = analyze_heap_dump(pid, output_dir)
        if dump_file:
            print(f"Heap dump saved to: {dump_file}")
            print("Analyze with: jmap -heap <pid> or VisualVM")
    
    if not (args.monitor or args.gc_logs or args.heap_dump):
        snapshot = get_jstat_memory(pid)
        if snapshot:
            heap_pct = (snapshot.heap_used_mb / snapshot.heap_max_mb * 100) if snapshot.heap_max_mb > 0 else 0
            print(f"\nCurrent Memory Status:")
            print(f"  Heap Used:    {snapshot.heap_used_mb:.1f} MB")
            print(f"  Heap Max:     {snapshot.heap_max_mb:.1f} MB")
            print(f"  Heap Usage:   {heap_pct:.1f}%")
            print(f"  Non-Heap:     {snapshot.non_heap_used_mb:.1f} MB")
            print(f"  GC Count:     {snapshot.gc_count}")
            print(f"  GC Time:      {snapshot.gc_time_ms} ms")
            
            heap_info = get_jmap_heap(pid)
            if heap_info:
                print(f"\nHeap Breakdown:")
                if 'eden_used_kb' in heap_info:
                    print(f"  Eden:          {heap_info['eden_used_kb']/1024:.1f} MB")
                if 'survivor_used_kb' in heap_info:
                    print(f"  Survivor:      {heap_info['survivor_used_kb']/1024:.1f} MB")
                if 'tenured_used_kb' in heap_info:
                    print(f"  Tenured:       {heap_info['tenured_used_kb']/1024:.1f} MB")
                if 'metaspace_used_kb' in heap_info:
                    print(f"  Metaspace:     {heap_info['metaspace_used_kb']/1024:.1f} MB")


if __name__ == "__main__":
    main()
