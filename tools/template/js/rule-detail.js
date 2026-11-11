/**
 * @module rule-detail
 * @file rule-detail.js - Rule detail view for the analysis reports.
 * @description Renders one rule's findings into the detail panel when a rule
 * name is clicked, and clears it when a subsystem link is followed. Expects
 * RULE_DATA (the findings as JSON) and LOCAL_MODE (a boolean) to be set before
 * this loads; in local mode the file paths become file:// links, which only a
 * page opened from disk can follow.
 * @author dr|z3d
 * @license AGPL3 or later
 */

let activeRule = null;

document.addEventListener("click", function(e) {
  // Click on sub-system link in navbar — close rule view first
  const a = e.target.closest("a[data-sub]");
  if (a) {
    e.preventDefault();
    hideRule();
    const tgt = document.getElementById(a.dataset.sub);
    if (tgt) {
      document.querySelectorAll("details[open]").forEach(function(o) { o.removeAttribute("open"); });
      tgt.setAttribute("open", "");
      setTimeout(function() { tgt.scrollIntoView({ behavior: "smooth", block: "start" }); }, 50);
    }
    return;
  }
  // Click on rule name in summary table — show rule detail view
  const rl = e.target.closest("[data-rule]");
  if (rl) {
    e.preventDefault();
    showRule(rl.dataset.rule);
    return;
  }
});

function esc(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function showRule(rule) {
  const d = RULE_DATA[rule];
  if (!d) return;
  activeRule = rule;
  const c = document.getElementById("rule-filter");
  const v = document.getElementById("violations");
  v.style.display = "none";
  c.style.display = "block";
  let h = '<div class="tabletitle">' + esc(rule) + ' <span class="badge">' + d.length + '</span></div>';
  let curFile = "";

  // Column header varies by report type
  const isBug = document.body.id === "spotbugs";
  const colLabel = isBug ? "Bug Type" : "Rule";

  for (let i = 0; i < d.length; i++) {
    const f = d[i][0], x = d[i][1];
    if (f !== curFile) {
      curFile = f;
      let pathHtml = '<span class="path">' + esc(f) + '</span>';
      if (LOCAL_MODE) pathHtml = '<a href="file://' + f + '" class="file-link">' + pathHtml + '</a>';
      h += '<h3>' + pathHtml + '</h3>';
      h += '<table class="warningtable"><tr><th class="line">Line</th><th>Message</th><th class="rule-category">Category</th><th class="rule-doc">Doc</th></tr>';
    }
    const rng = x.begin === x.end ? x.begin : x.begin + '-' + x.end;
    let dl = '';
    if (x.url) dl = '<a href="' + esc(x.url) + '" target="_blank" rel="noopener" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation"></span></a>';
    const details = [];
    if (x.class) details.push('<b>Class:</b> ' + esc(x.class));
    if (x.method) details.push('<b>Method:</b> ' + esc(x.method));
    const hasDetail = details.length > 0 || (x.snippet && x.snippet.length > 0);
    const vid = 'rv' + Math.abs(hashCode(f + x.begin + rule));
    if (hasDetail) h += '<tr class="tablerow' + (i % 2) + '" data-detail="' + vid + '">';
    else h += '<tr class="tablerow' + (i % 2) + '">';
    h += '<td class="line priority-cell p' + x.priority + '">' + esc(rng) + '</td>';
    h += '<td>' + esc(x.msg) + '</td>';
    h += '<td class="rule-category">' + esc(x.ruleset || x.category || '') + '</td>';
    h += '<td class="rule-doc">' + dl + '</td></tr>';
    if (hasDetail) {
      h += '<tr class="detailrow' + (i % 2) + ' hidden"><td colspan="4">';
      h += '<div id="' + vid + '" style="display:none" class="detail-content">';
      if (details.length) h += details.join(' &middot; ');
      if (x.snippet && x.snippet.length) h += '<pre class="snippet"><code>' + esc(x.snippet) + '</code></pre>';
      h += '</div></td></tr>';
    }
    if (i + 1 >= d.length || d[i + 1][0] !== f) h += '</table>';
  }
  c.innerHTML = h;
  c.scrollIntoView({ behavior: "smooth", block: "start" });
}

function hashCode(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) {
    h = ((h << 5) - h) + s.charCodeAt(i);
    h |= 0;
  }
  return h;
}

function hideRule() {
  activeRule = null;
  document.getElementById("rule-filter").style.display = "none";
  document.getElementById("violations").style.display = "block";
}
