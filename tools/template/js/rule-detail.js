/* I2P+ Rule Detail View - PMD, SpotBugs, Checkstyle */
/* Expects: RULE_DATA (JSON), LOCAL_MODE (bool) to be set before this loads */

var activeRule = null;

document.addEventListener("click", function(e) {
  // Click on sub-system link in navbar — close rule view first
  var a = e.target.closest("a[data-sub]");
  if (a) {
    e.preventDefault();
    hideRule();
    var tgt = document.getElementById(a.dataset.sub);
    if (tgt) {
      document.querySelectorAll("details[open]").forEach(function(o) { o.removeAttribute("open"); });
      tgt.setAttribute("open", "");
      setTimeout(function() { tgt.scrollIntoView({ behavior: "smooth", block: "start" }); }, 50);
    }
    return;
  }
  // Click on rule name in summary table — show rule detail view
  var rl = e.target.closest("[data-rule]");
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
  var d = RULE_DATA[rule];
  if (!d) return;
  activeRule = rule;
  var c = document.getElementById("rule-filter");
  var v = document.getElementById("violations");
  v.style.display = "none";
  c.style.display = "block";
  var h = '<div class="tabletitle">' + esc(rule) + ' <span class="badge">' + d.length + '</span></div>';
  var curFile = "";

  // Column header varies by report type
  var isBug = document.body.id === "spotbugs";
  var colLabel = isBug ? "Bug Type" : "Rule";

  for (var i = 0; i < d.length; i++) {
    var f = d[i][0], x = d[i][1];
    if (f !== curFile) {
      curFile = f;
      var pathHtml = '<span class="path">' + esc(f) + '</span>';
      if (LOCAL_MODE) pathHtml = '<a href="file://' + f + '" class="file-link">' + pathHtml + '</a>';
      h += '<h3>' + pathHtml + '</h3>';
      h += '<table class="warningtable"><tr><th class="line">Line</th><th>Message</th><th class="rule-category">Category</th><th class="rule-doc">Doc</th></tr>';
    }
    var rng = x.begin === x.end ? x.begin : x.begin + '-' + x.end;
    var dl = '';
    if (x.url) dl = '<a href="' + esc(x.url) + '" target="_blank" class="rule-doc-link"><span class="rule-doc-icon" title="Rule documentation"></span></a>';
    var details = [];
    if (x.class) details.push('<b>Class:</b> ' + esc(x.class));
    if (x.method) details.push('<b>Method:</b> ' + esc(x.method));
    var hasDetail = details.length > 0 || (x.snippet && x.snippet.length > 0);
    var vid = 'rv' + Math.abs(hashCode(f + x.begin + rule));
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
  var h = 0;
  for (var i = 0; i < s.length; i++) {
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
