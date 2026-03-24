/* I2P+ Analysis Reports - Shared JavaScript */

// Close other open details when one is opened
document.addEventListener("click", function(e) {
  var s = e.target.closest("summary");
  if (s) {
    var d = s.parentElement;
    if (d && d.tagName === "DETAILS" && !d.hasAttribute("open")) {
      document.querySelectorAll("details[open]").forEach(function(o) {
        if (o !== d) o.removeAttribute("open");
      });
    }
    return;
  }
  // Expand/collapse detail rows
  var tr = e.target.closest("tr[data-detail]");
  if (tr) {
    var el = document.getElementById(tr.dataset.detail);
    if (el) {
      var showing = el.style.display === "none";
      el.style.display = showing ? "block" : "none";
      el.closest("tr").classList.toggle("hidden", !showing);
    }
    return;
  }
});
