/**
 * @module source-filter
 * @file source-filter.js - Source and severity filter for the combined report.
 * @description Narrows the report-all tables to the analysis tools and severity
 * levels the reader picks from the summary header, and recounts the visible
 * rows in each table so the totals shown match what is on screen.
 * @author dr|z3d
 * @license AGPL3 or later
 */

let activeSource = null;
const activeSeverities = new Set();

document.addEventListener("click", function(e) {
  const sevTh = e.target.closest("#summary th .severity-error, #summary th .severity-warning, #summary th .severity-info");
  if (sevTh) {
    e.preventDefault();
    const th = sevTh.closest("th");
    const sev = sevTh.classList.contains("severity-error") ? "p1"
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
  const row = e.target.closest(".src-filter-row");
  if (row) {
    e.preventDefault();
    const src = row.dataset.source;
    activeSource = (activeSource === src) ? null : src;
    applyFilters();
    return;
  }
  const sub = e.target.closest("[data-sub]");
  if (sub) {
    e.preventDefault();
    const tgt = document.getElementById(sub.dataset.sub);
    if (tgt) {
      document.querySelectorAll("details[open]").forEach(function(o) { o.removeAttribute("open"); });
      tgt.setAttribute("open", "");
      setTimeout(function() { tgt.scrollIntoView({ behavior: "smooth", block: "start" }); }, 50);
    }
  }
});

function applyFilters() {
  const hasSource = activeSource !== null;
  const hasSev = activeSeverities.size > 0;

  // Source filter on summary rows
  document.querySelectorAll(".src-filter-row").forEach(function(r) {
    r.classList.toggle("active", hasSource && r.dataset.source === activeSource);
  });

  // Combined filter on violation rows
  document.querySelectorAll("tr[data-source]:not(.src-filter-row)").forEach(function(tr) {
    let show = true;
    if (hasSource && tr.dataset.source !== activeSource) show = false;
    if (hasSev && !activeSeverities.has(tr.dataset.severity)) show = false;
    tr.classList.toggle("source-hidden", !show);
  });

  // Hide empty file headers
  document.querySelectorAll("#violations h3").forEach(function(h3) {
    const tbl = h3.nextElementSibling;
    if (!tbl || !tbl.classList.contains("warningtable")) return;
    const vis = tbl.querySelectorAll("tr[data-source]:not(.source-hidden):not([class*=detailrow])");
    h3.style.display = vis.length ? "" : "none";
    tbl.style.display = vis.length ? "" : "none";
  });

  // Hide empty section groups
  document.querySelectorAll("#violations details").forEach(function(d) {
    const vis = d.querySelectorAll("tr[data-source]:not(.source-hidden):not([class*=detailrow])");
    d.style.display = (hasSource || hasSev) ? (vis.length ? "" : "none") : "";
  });

  // Update navbar badges
  document.querySelectorAll("#navbar span").forEach(function(span) {
    const link = span.querySelector("a[data-sub]");
    if (!link) return;
    const section = document.getElementById(link.dataset.sub);
    if (!section) return;
    const vis = section.querySelectorAll("tr[data-source]:not(.source-hidden):not([class*=detailrow])");
    const badge = link.querySelector(".badge");
    const filtered = hasSource || hasSev;
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
