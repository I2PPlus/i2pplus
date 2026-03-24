/* I2P+ Source Filter - Combined report (report-all) */
/* Filters rows by analysis source (PMD, Checkstyle, CodeQL, SpotBugs) */

var activeSource = null;

document.addEventListener("click", function(e) {
  var row = e.target.closest(".src-filter-row");
  if (row) {
    e.preventDefault();
    var src = row.dataset.source;
    activeSource = (activeSource === src) ? null : src;
    filterBySource(activeSource);
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

function filterBySource(src) {
  // Highlight active filter row
  document.querySelectorAll(".src-filter-row").forEach(function(r) {
    r.classList.toggle("active", src !== null && r.dataset.source === src);
  });
  // Show/hide violation rows
  document.querySelectorAll("tr[data-source]:not(.src-filter-row)").forEach(function(tr) {
    if (src === null) { tr.classList.remove("source-hidden"); }
    else { tr.classList.toggle("source-hidden", tr.dataset.source !== src); }
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
    if (src === null) { d.style.display = ""; return; }
    var vis = d.querySelectorAll("tr[data-source]:not(.source-hidden):not([class*=detailrow])");
    d.style.display = vis.length ? "" : "none";
  });
}
