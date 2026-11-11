/**
 * @module shared
 * @file shared.js - Shared click handling for the analysis report pages.
 * @description One delegated listener for every report: opening a details block
 * closes the others so a long report stays readable, and a row marked
 * data-detail shows or hides the detail row it names.
 * @author dr|z3d
 * @license AGPL3 or later
 */

// Close other open details when one is opened
document.addEventListener("click", function(e) {
  const s = e.target.closest("summary");
  if (s) {
    const d = s.parentElement;
    if (d && d.tagName === "DETAILS" && !d.hasAttribute("open")) {
      document.querySelectorAll("details[open]").forEach(function(o) {
        if (o !== d) o.removeAttribute("open");
      });
    }
    return;
  }
  // Expand/collapse detail rows
  const tr = e.target.closest("tr[data-detail]");
  if (tr) {
    const el = document.getElementById(tr.dataset.detail);
    if (el) {
      const showing = el.style.display === "none";
      el.style.display = showing ? "block" : "none";
      el.closest("tr").classList.toggle("hidden", !showing);
    }
    return;
  }
});
