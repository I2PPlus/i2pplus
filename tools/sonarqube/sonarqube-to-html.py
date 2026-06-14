#!/usr/bin/env python3
"""Fetch SonarQube issues from API and generate HTML report using shared template.

Usage: sonarqube-to-html.py --token <token> --url <sonar_url> --project <project_key> [--output <output.html>]
"""

import json
import os
import sys
import urllib.parse
import urllib.request
import urllib.error

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "template"))
from common import (
    escape,
    load_css,
    load_favicon,
    load_js,
    get_code_snippet,
    html_meta_line,
    CONFIG,
)

SQ_SEV_CLASS = {
    "blocker": "p1",
    "critical": "p2",
    "major": "p3",
    "minor": "p4",
    "info": "p5",
}
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


SUBSYSTEM_MAP = [
    ("apps/routerconsole/", "Router"),
    ("apps/susimail/", "SusiMail"),
    ("apps/i2ptunnel/", "I2ptunnel"),
    ("apps/i2psnark/", "I2PSnark"),
    ("apps/addressbook/", "Addressbook"),
    ("apps/susidns/", "SusiDNS"),
    ("apps/desktopgui/", "DesktopGui"),
    ("apps/imagegen/", "Imagegen"),
    ("apps/sam/", "Sam"),
    ("apps/streaming/", "Streaming"),
    ("apps/ministreaming/", "Ministreaming"),
    ("apps/i2pcontrol/", "I2pcontrol"),
    ("apps/", "Apps"),
    ("router/java/src/net/i2p/router/networkdb/kademlia/", "Kademlia"),
    ("router/java/src/net/i2p/router/", "Router"),
    ("core/java/src/net/i2p/data/", "Data"),
    ("core/java/src/net/i2p/client/", "Client"),
    ("core/java/src/net/i2p/crypto/", "Crypto"),
    ("core/java/src/net/i2p/util/", "Utilities"),
    ("core/java/src/net/i2p/", "Core"),
]


def subsystem_for_path(file_path):
    if not file_path:
        return "Other"
    for prefix, name in SUBSYSTEM_MAP:
        if file_path.startswith(prefix):
            return name
    return "Other"


def fetch_issues(base_url, token, project_key):
    """Fetch all issues from SonarQube API with multi-split pagination.

    Splits queries by (type x severity) to stay under the 10k-per-query
    Community Edition limit.  If a single combo still exceeds 10k, further
    splits by individual rule using the rules facet.
    """
    issues = []
    ps = 500
    MAX_PER_QUERY = 10000

    def mkurl(params):
        parts = [
            f"componentKeys={urllib.parse.quote(project_key)}",
            f"ps={ps}",
            "additionalFields=_all",
        ]
        for k, v in params.items():
            if isinstance(v, list):
                for item in v:
                    parts.append(f"{k}={urllib.parse.quote(str(item))}")
            else:
                parts.append(f"{k}={urllib.parse.quote(str(v))}")
        return f"{base_url}/api/issues/search?" + "&".join(parts)

    def do_req(params):
        url = mkurl(params)
        req = urllib.request.Request(url)
        req.add_header("Authorization", f"Bearer {token}")
        try:
            resp = urllib.request.urlopen(req)
            return json.loads(resp.read())
        except urllib.error.HTTPError as e:
            body = e.read().decode()
            if e.code == 400 and "first 10000 results" in body:
                return None  # signal: hit limit
            print(f"API error: {e.code} {e.reason}", file=sys.stderr)
            sys.exit(1)

    def paginate(params):
        """Paginate a single query, returning all results or None if split needed."""
        result = []
        data = do_req({**params, "p": 1})
        if data is None:
            return None
        total = data.get("total", 0)
        if total > MAX_PER_QUERY:
            return None
        result.extend(data.get("issues", []))
        page = 2
        while len(result) < total:
            data = do_req({**params, "p": page})
            if data is None:
                return result  # partial – better than nothing
            batch = data.get("issues", [])
            if not batch:
                break
            result.extend(batch)
            page += 1
        return result

    def fetch_rules(params):
        """Get list of rule keys for a given query via facets."""
        data = do_req({**params, "p": 1, "facets": "rules"})
        if data is None:
            return []
        for f in data.get("facets", []):
            if f.get("property") == "rules":
                return [v["val"] for v in f.get("values", [])]
        return []

    def fetch_combo(type_val, severity_val):
        params = {}
        if type_val:
            params["types"] = type_val
        if severity_val:
            params["severities"] = severity_val
        result = paginate(params)
        if result is not None:
            return result
        # Hit 10k limit – split by rule
        rules = fetch_rules(params)
        if not rules:
            print(
                f"Warning: combo {type_val}/{severity_val} exceeds 10k but no rules facet available",
                file=sys.stderr,
            )
            return []
        out = []
        for rule in rules:
            rp = {**params, "rules": rule}
            batch = paginate(rp)
            if batch is not None:
                out.extend(batch)
            else:
                # shouldn't happen – single rules are always < 10k
                print(
                    f"Warning: rule {rule} still over 10k in {type_val}/{severity_val}",
                    file=sys.stderr,
                )
        return out

    # Split into type x severity combos – each is < 10k except CODE_SMELL+MINOR
    for issue_type in ["BUG", "VULNERABILITY", "CODE_SMELL"]:
        for severity in ["BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO"]:
            issues.extend(fetch_combo(issue_type, severity))

    # Deduplicate by key – overlapping rules could yield duplicates
    seen = set()
    deduped = []
    for issue in issues:
        k = issue.get("key", "")
        if k and k not in seen:
            seen.add(k)
            deduped.append(issue)
    print(
        f"Fetched {len(deduped)} unique issues (deduped from {len(issues)})",
        file=sys.stderr,
    )
    return deduped


def rule_url(rule_key, sonar_url="https://cloud-ci.sgs.com/sonar"):
    """Convert SonarQube rule key (e.g. java:S116) to coding_rules page URL."""
    import urllib.parse
    import re

    if not isinstance(rule_key, str):
        return None
    m = re.match(r"^(\w+):(S\d+)$", rule_key)
    if not m:
        return None
    base = sonar_url.rstrip("/")
    lang = m.group(1)
    rule_id = m.group(2)
    return f"{base}/coding_rules?languages={lang}&q={rule_id}&open={urllib.parse.quote(rule_key)}"


def build_html(
    issues, local=False, exclude_rules=None, sonar_url="https://cloud-ci.sgs.com/sonar"
):
    """Generate HTML report from SonarQube issues."""
    original_total = len(issues)
    if exclude_rules:
        filtered = [i for i in issues if i.get("rule", "") not in exclude_rules]
        skipped = len(issues) - len(filtered)
        issues = filtered
    total = len(issues)
    file_set = set()
    for issue in issues:
        comp = issue.get("component", "")
        fpath = comp.replace("net.i2p.router:i2pplus:", "")
        file_set.add(fpath)
        sub = subsystem_for_path(fpath)
        issue["_subsystem"] = sub
        issue["_file"] = fpath

    file_count = len(file_set)

    # Group by subsystem
    by_sub = {}
    for issue in issues:
        sub = issue["_subsystem"]
        by_sub.setdefault(sub, []).append(issue)

    # Sort subsystems by count descending
    sub_order = sorted(by_sub.keys(), key=lambda s: len(by_sub[s]), reverse=True)

    # Group by type for "By Rule" summary
    by_type = {}
    for issue in issues:
        t = issue.get("type", "CODE_SMELL")
        by_type.setdefault(t, []).append(issue)

    # Build RULE_DATA JS object (keyed by type)
    # Each entry: [file, {line, sev, msg, subsystem, snippet}]
    rule_data = {}
    for issue in issues:
        t = issue.get("type", "CODE_SMELL")
        rule_data.setdefault(t, [])
        fpath = issue["_file"]
        line = (
            issue.get("line", 0) or issue.get("textRange", {}).get("startLine", 0) or 0
        )
        sev = issue.get("severity", "MAJOR")
        msg = issue.get("message", "")
        snippet = ""
        if line and fpath:
            full_path = os.path.join(PROJECT_ROOT, fpath)
            snippet = get_code_snippet(full_path, line)
        entry = {
            "line": str(line),
            "sev": sev,
            "msg": msg,
            "rule": issue.get("rule", ""),
            "ruleUrl": rule_url(issue.get("rule", ""), sonar_url),
            "subsystem": issue["_subsystem"],
            "snippet": snippet,
        }
        rule_data[t].append([fpath, entry])

    # Build per-subsystem counts per type for summary table
    type_subs = {}
    for issue in issues:
        t = issue.get("type", "CODE_SMELL")
        sub = issue["_subsystem"]
        type_subs.setdefault(t, {}).setdefault(sub, 0)
        type_subs[t][sub] += 1

    # Count distinct files per type
    type_files = {}
    for issue in issues:
        t = issue.get("type", "CODE_SMELL")
        type_files.setdefault(t, set()).add(issue["_file"])

    prefix = CONFIG["report_prefix"]
    body_id = "sonarqube-page"
    html = f"<h1>{escape(prefix)} SonarQube Analysis</h1>"
    html += '<div id="content">'
    skipped = max(0, original_total - total)
    tool_info = "SonarQube Community Build 26.6"
    if skipped:
        tool_info += f" ({skipped} excluded, {original_total} total)"
    html += html_meta_line(total, file_count, tool_info)

    # Navbar — by subsystem
    html += '<div id="navbar">'
    for sub in sub_order:
        cnt = len(by_sub[sub])
        html += f'<span><a href="#sub-{sub}" data-sub="sub-{sub}">{sub} <span class="badge">{cnt}</span></a></span>'
    html += "</div>"

    # "By Rule" summary table — grouped by type
    html += '<div class="tabletitle"><a name="rules">By Rule</a></div>\n'
    html += '<table id="summary">\n<tr>'
    html += "<th>Rule</th><th>Sub-systems</th>"
    html += '<th class="summary-count">Violations</th>'
    html += '<th class="summary-count">Files</th>'
    html += "</tr>\n"

    for idx, (sev_label, sev_class) in enumerate(
        [
            ("BUG", "p1"),
            ("VULNERABILITY", "p2"),
            ("CODE_SMELL", "p3"),
        ]
    ):
        t = sev_label
        if t not in by_type:
            continue
        items = by_type[t]
        subs = type_subs.get(t, {})
        sorted_subs = sorted(subs.items(), key=lambda x: x[1], reverse=True)
        subs_html = ", ".join(
            f'<a href="#sub-{s}" data-sub="sub-{s}">{s}</a> ({c})'
            for s, c in sorted_subs
        )
        file_cnt = len(type_files.get(t, set()))
        html += f'<tr class="tablerow{idx % 2}">'
        html += f'<td><a href="#" data-rule="{escape(t)}"><span class="line priority-cell {sev_class}" style="min-width:120px;display:inline-block;border-radius:2px">{escape(t)}</span></a></td>'
        html += f'<td class="subs">{subs_html}</td>'
        html += f'<td class="summary-count">{len(items)}</td>'
        html += f'<td class="summary-count">{file_cnt}</td>'
        html += "</tr>\n"

    html += f'<tr class="tablerow1"><td><b>Total</b></td><td></td><td class="summary-count"><b>{total}</b></td><td class="summary-count"><b>{file_count}</b></td></tr>\n'
    html += "</table>\n"

    # Rule filter view (hidden, populated by JS)
    html += '<div id="rule-filter" style="display:none"></div>\n'

    # Main content: violations by subsystem
    html += '<div id="violations">\n'
    html += '<div class="tabletitle"><a name="violations">Violations</a></div>\n'

    for sub in sub_order:
        items = by_sub[sub]
        # Group files within subsystem
        sub_files = {}
        for issue in items:
            fp = issue["_file"]
            sub_files.setdefault(fp, []).append(issue)

        # Sort files by path
        sorted_files = sorted(sub_files.items())

        file_count_in_sub = len(sorted_files)
        html += f'<details id="sub-{sub}">\n'
        html += f'<summary><div class="tabletitle"><a name="sub-{sub}">{sub}</a> <span class="badge" style="min-width:92px">{len(items)} / {file_count_in_sub}</span></div></summary>\n'

        for fpath, file_issues in sorted_files:
            first_line = min(
                (
                    issue.get("line", 0)
                    or issue.get("textRange", {}).get("startLine", 0)
                    or 0
                )
                for issue in file_issues
            )
            if local:
                abs_path = os.path.join(PROJECT_ROOT, fpath)
                loc = f":{first_line}" if first_line else ""
                file_link = f"file://{abs_path}{loc}"
                path_html = f'<a href="{escape(file_link)}" class="file-link">{escape(fpath)}</a>'
            else:
                path_html = escape(fpath)
            html += f'<h3><span class="path">{path_html}</span><span class="badge">{len(file_issues)}</span></h3>\n'
            html += '<table class="warningtable">\n<tr>'
            html += '<th class="line">Line</th><th>Rule</th><th>Message</th><th>File</th><th class="rule-doc">Doc</th>'
            html += "</tr>\n"

            for i, issue in enumerate(file_issues):
                line = (
                    issue.get("line", 0)
                    or issue.get("textRange", {}).get("startLine", 0)
                    or 0
                )
                sev = issue.get("severity", "MAJOR")
                msg = escape(issue.get("message", ""))
                issue_type = escape(issue.get("type", ""))
                short_path = fpath
                link = ""
                line_link = ""
                if local:
                    full_path = os.path.join(PROJECT_ROOT, fpath) if fpath else ""
                    short_path = (
                        os.path.relpath(full_path, PROJECT_ROOT) if full_path else fpath
                    )
                    link = (
                        f"file://{os.path.join(PROJECT_ROOT, fpath)}" if fpath else ""
                    )
                    if fpath and line:
                        line_link = link + f":{line}"

                snippet = ""
                if line and fpath:
                    full_path = os.path.join(PROJECT_ROOT, fpath)
                    snippet = get_code_snippet(full_path, line)

                vid = f"sq{abs(hash(fpath + str(line) + str(i)))}"
                sev_cls = SQ_SEV_CLASS.get(sev.lower(), "p3")
                row_cls = f"tablerow{i % 2}"

                if snippet:
                    html += f'<tr class="{row_cls}" data-detail="{vid}">\n'
                else:
                    html += f'<tr class="{row_cls}">\n'
                if line_link:
                    html += f'<td class="line priority-cell {sev_cls}"><a href="{escape(line_link)}" class="line-link">{line}</a></td>\n'
                else:
                    html += f'<td class="line priority-cell {sev_cls}">{line}</td>\n'
                html += f'<td style="width:1%;min-width:80px;text-align:center">{issue_type}</td>\n'
                html += f'<td style="width:50%">{msg}</td>\n'
                html += f"<td>"
                if link:
                    html += f'<a href="{link}">{escape(short_path)}</a>'
                else:
                    html += escape(short_path)
                url = rule_url(issue.get("rule", ""), sonar_url)
                if url:
                    html += f'<td class="rule-doc"><a href="{url}" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation: {escape(issue.get("rule", ""))}"></span></a></td>\n'
                else:
                    html += '<td class="rule-doc"></td>\n'
                html += "</tr>\n"

                if snippet:
                    html += f'<tr class="detailrow{i % 2} hidden"><td colspan="5">'
                    html += (
                        f'<div id="{vid}" style="display:none" class="detail-content">'
                    )
                    html += f'<pre class="snippet" style="line-height:1"><code>{snippet}</code></pre>'
                    html += "</div></td></tr>\n"

            html += "</table>\n"

        html += "</details>\n"

    html += "</div>\n"
    html += "</div>"

    # Inline JS for rule filtering and subsystem navigation
    rule_data_json = json.dumps(rule_data).replace("</script>", "<\\/script>")
    inline_js = f"""
document.addEventListener("click",function(e){{
  var a=e.target.closest("a[data-sub]");
  if(a){{
    e.preventDefault();
    hideRule();
    var tgt=document.getElementById(a.dataset.sub);
    if(tgt){{
      document.querySelectorAll("details[open]").forEach(function(o){{if(o!==tgt)o.removeAttribute("open")}});
      tgt.setAttribute("open","");
      setTimeout(function(){{tgt.scrollIntoView({{behavior:"smooth",block:"start"}})}},50);
    }}
    return;
  }}
  var rl=e.target.closest("[data-rule]");
  if(rl){{
    e.preventDefault();
    showRule(rl.dataset.rule);
    return;
  }}
}});

var LOCAL_MODE={"true" if local else "false"};
var RULE_DATA={rule_data_json};
var activeRule=null;

function showRule(rule){{
  var d=RULE_DATA[rule];
  if(!d)return;
  activeRule=rule;
  var c=document.getElementById("rule-filter");
  var v=document.getElementById("violations");
  v.style.display="none";
  c.style.display="block";
  var h='<div class="tabletitle">'+esc(rule)+' <span class="badge">'+d.length+'</span></div>';
    var curFile="";
  for(var i=0;i<d.length;i++){{
    var f=d[i][0],x=d[i][1];
    if(f!==curFile){{
      curFile=f;
      var pathHtml='<span class="path">'+esc(f)+'</span>';
      h+='<h3>'+pathHtml+'</h3>';
      h+='<table class="warningtable"><tr><th class="line">Line</th><th>Rule</th><th>Message</th><th>Sub-system</th><th>File</th><th class="rule-doc">Doc</th></tr>';
    }}
    var sevCls=SQ_SEV_CLASS[x.sev.toLowerCase()]||"p3";
    var vid='rv'+Math.abs(hashCode(f+x.line+rule));
    h+='<tr class="tablerow'+(i%2)+'" data-detail="'+vid+'">';
    if(LOCAL_MODE&&x.line){{
      h+='<td class="line priority-cell '+sevCls+'"><a href="file://'+esc(f)+':'+esc(x.line)+'" class="line-link">'+esc(x.line)+'</a></td>';
    }}else{{
      h+='<td class="line priority-cell '+sevCls+'">'+esc(x.line)+'</td>';
    }}
    h+='<td style="width:1%;min-width:80px;text-align:center">'+esc(x.rule)+'</td>';
    h+='<td>'+esc(x.msg)+'</td>';
    h+='<td class="rule-category">'+esc(x.subsystem)+'</td>';
    if(LOCAL_MODE){{
      h+='<td><a href="file://'+esc(f)+'">'+esc(f)+'</a></td>';
    }}else{{
      h+='<td>'+esc(f)+'</td>';
    }}
    var dl='';if(x.ruleUrl)dl='<a href="'+esc(x.ruleUrl)+'" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation: '+x.rule+'"></span></a>';
    h+='<td class="rule-doc">'+dl+'</td>';
    h+='</tr>';
    if(x.snippet&&x.snippet.length){{
      h+='<tr class="detailrow'+(i%2)+' hidden"><td colspan="6">';
      h+='<div id="'+vid+'" style="display:none" class="detail-content">';
      h+='<pre class="snippet"><code>'+x.snippet+'</code></pre>';
      h+='</div></td></tr>';
    }}
    if(i+1>=d.length||d[i+1][0]!==f)h+='</table>';
  }}
  c.innerHTML=h;
  c.scrollIntoView({{behavior:"smooth",block:"start"}});
}}

function hideRule(){{
  activeRule=null;
  document.getElementById("rule-filter").style.display="none";
  document.getElementById("violations").style.display="block";
}}

function esc(s){{return s.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;");}}

function hashCode(s){{var h=0;for(var i=0;i<s.length;i++){{h=((h<<5)-h)+s.charCodeAt(i);h|=0;}}return h;}}

var SQ_SEV_CLASS={{"blocker":"p1","critical":"p2","major":"p3","minor":"p4","info":"p5"}};
"""

    css = load_css()
    favicon = load_favicon()
    favicon_tag = (
        f'<link rel="icon" type="image/svg+xml" href="{favicon}">' if favicon else ""
    )
    js = load_js("shared.js")
    full_html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>{escape(prefix)} | SonarQube Analysis</title>
{favicon_tag}
<style>{css}</style>
<script>{js}</script>
</head>
<body id="{body_id}">
{html}
<script>{inline_js}</script>
</body>
</html>"""
    return full_html


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Generate SonarQube HTML report")
    parser.add_argument("--token", help="SonarQube API token")
    parser.add_argument(
        "--url", default="http://localhost:11199", help="SonarQube server URL"
    )
    parser.add_argument(
        "--project", default="net.i2p.router:i2pplus", help="Project key"
    )
    parser.add_argument(
        "--output",
        action="append",
        default=[],
        help="Output HTML file (can specify multiple)",
    )
    parser.add_argument("--local", action="store_true", help="Use file:// links")
    parser.add_argument(
        "--exclude-rules",
        default="",
        help="Comma-separated rule keys to exclude (e.g. java:S116,java:S2293)",
    )
    parser.add_argument(
        "--cached-issues",
        default="",
        help="Path to cached issues JSON (skip API fetch)",
    )
    parser.add_argument(
        "--save-cache",
        default="",
        help="Save fetched issues to this JSON file for offline use",
    )
    parser.add_argument(
        "--sonar-url",
        default="https://cloud-ci.sgs.com/sonar",
        help="SonarQube server URL for rule documentation links",
    )
    args = parser.parse_args()

    exclude_rules = (
        set(r.strip() for r in args.exclude_rules.split(",") if r.strip())
        if args.exclude_rules
        else set()
    )

    if args.cached_issues:
        cache_path = (
            os.path.join(PROJECT_ROOT, args.cached_issues)
            if not os.path.isabs(args.cached_issues)
            else args.cached_issues
        )
        print(f"Loading issues from {cache_path} (offline, no server needed)...")
        with open(cache_path) as f:
            issues = json.load(f)
        print(f"Loaded {len(issues)} issues from cache")
    else:
        if not args.token:
            print(
                "Error: --token required for API fetch. Use --cached-issues for offline mode.",
                file=sys.stderr,
            )
            sys.exit(1)
        print(f"Fetching issues from {args.url} (requires live server)...")
        issues = fetch_issues(args.url.rstrip("/"), args.token, args.project)
        print(f"Found {len(issues)} issues")

    if args.save_cache:
        cache_path = (
            os.path.join(PROJECT_ROOT, args.save_cache)
            if not os.path.isabs(args.save_cache)
            else args.save_cache
        )
        os.makedirs(os.path.dirname(cache_path), exist_ok=True)
        with open(cache_path, "w") as f:
            json.dump(issues, f)
        print(f"Saved issue cache to {cache_path}")

    from concurrent.futures import ThreadPoolExecutor, as_completed

    def gen_and_write(output, local):
        html = build_html(
            issues, local=local, exclude_rules=exclude_rules, sonar_url=args.sonar_url
        )
        output_path = os.path.join(PROJECT_ROOT, output)
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        with open(output_path, "w") as f:
            f.write(html)
        rel = os.path.relpath(output_path, PROJECT_ROOT)
        return rel

    outputs = []
    for out in args.output:
        is_local = args.local or out.endswith("-local.html")
        outputs.append((out, is_local))

    with ThreadPoolExecutor(max_workers=len(outputs)) as pool:
        futures = {
            pool.submit(gen_and_write, out, local): out for out, local in outputs
        }
        for f in as_completed(futures):
            print(f"Report written to {f.result()}")


if __name__ == "__main__":
    main()
