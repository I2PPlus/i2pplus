/* I2P+ Source Filter - Combined report (report-all) */
/* Filters rows by analysis source and/or severity level */

var activeSource = null;
var activeSeverities = new Set();

document.addEventListener("click", function(e) {
  var sevTh = e.target.closest("#summary th .severity-error, #summary th .severity-warning, #summary th .severity-info");
  if (sevTh) {
    e.preventDefault();
    var th = sevTh.closest("th");
    var sev = sevTh.classList.contains("severity-error") ? "p1"
            : sevTh.classList.contains("severity-warning") ? "p2" : "p3";
    if (activeSeverities.has(sev)) {
      activeSeverities.delete(sev);
      th.classList.remove("filtered");
    } else {
      activeSeverities.add(sev);
      th.classList.add("filtered");
    }
    applyFilters();
    return;
  }
  var row = e.target.closest(".src-filter-row");
  if (row) {
    e.preventDefault();
    var src = row.dataset.source;
    activeSource = (activeSource === src) ? null : src;
    applyFilters();
    return;
  }
  var sub = e.target.closest("[data-sub]");
  if (sub) {
    e.preventDefault();
    var tgt = document.getElementById(sub.dataset.sub);
    if (tgt) {
      document.querySelectorAll("details[open]").forEach(function(o) { o.removeAttribute("open"); });
      tgt.setAttribute("open", "");
      setTimeout(function() { tgt.scrollIntoView({ behavior: "smooth", block: "start" }); }, 50);
    }
  }
});

function applyFilters() {
  var hasSource = activeSource !== null;
  var hasSev = activeSeverities.size > 0;

  // Source filter on summary rows
  document.querySelectorAll(".src-filter-row").forEach(function(r) {
    r.classList.toggle("active", hasSource && r.dataset.source === activeSource);
  });

  // Combined filter on violation rows
  document.querySelectorAll("tr[data-source]:not(.src-filter-row)").forEach(function(tr) {
    var show = true;
    if (hasSource && tr.dataset.source !== activeSource) show = false;
    if (hasSev && !activeSeverities.has(tr.dataset.severity)) show = false;
    tr.classList.toggle("source-hidden", !show);
  });

  // Hide empty file headers
  document.querySelectorAll("#violations h3").forEach(function(h3) {
    var tbl = h3.nextElementSibling;
    if (!tbl || !tbl.classList.contains("warningtable")) return;
    var vis = tbl.querySelectorAll("tr[data-source]:not(.source-hidden):not([class*=detailrow])");
    h3.style.display = vis.length ? "" : "none";
    tbl.style.display = vis.length ? "" : "none";
  });

  // Hide empty section groups
  document.querySelectorAll("#violations details").forEach(function(d) {
    var vis = d.querySelectorAll("tr[data-source]:not(.source-hidden):not([class*=detailrow])");
    d.style.display = (hasSource || hasSev) ? (vis.length ? "" : "none") : "";
  });

  // Update navbar badges
  document.querySelectorAll("#navbar span").forEach(function(span) {
    var link = span.querySelector("a[data-sub]");
    if (!link) return;
    var section = document.getElementById(link.dataset.sub);
    if (!section) return;
    var vis = section.querySelectorAll("tr[data-source]:not(.source-hidden):not([class*=detailrow])");
    var badge = link.querySelector(".badge");
    var filtered = hasSource || hasSev;
    if (!filtered) {
      span.style.display = "";
      if (badge && badge.dataset.total) badge.textContent = badge.dataset.total;
    } else {
      span.style.display = vis.length ? "" : "none";
      if (badge) {
        if (!badge.dataset.total) badge.dataset.total = badge.textContent;
        badge.textContent = vis.length;
      }
    }
  });
}
