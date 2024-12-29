/* I2P+ configtunnels.js by dr|z3d */
/* Toggle display of tunnel count badges according to toggle state */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", () => {
  function showHideTunnelCounts() {
    const table = document.querySelector("#tunnelconfig");
    if (!table) return;
    setupToggles("#tunnelconfig thead", "#tunnelconfig thead+tbody", "table-row-group");

    table.addEventListener("click", function(event) {
      const clickTarget = event.target;
      const toggle = clickTarget.closest(".toggle");
      const headerLink =  clickTarget.closest(".toggle a");
      if (headerLink) {
        event.stopPropagation();
        headerLink.click();
      }
      if (!toggle || headerLink) {return;}
      console.log("closest clickTarget is: ", clickTarget);
      const tIn = toggle.querySelector(".th_title.left .tIn");
      const tOut = toggle.querySelector(".th_title.left .tOut");

      if (toggle.classList.contains("expanded")) {
        if (tIn) tIn.style.display = "none";
        if (tOut) tOut.style.display = "none";
      } else {
        if (tIn) tIn.removeAttribute("style");
        if (tOut) tOut.removeAttribute("style");
      }
    });

    const toggle =  document.querySelector(".toggle");
    const headerLink =  document.querySelector(".toggle a");
    headerLink.addEventListener("mouseenter", function(event) {
      headerlink.style.zIndex = "99999";
    });
  }
  showHideTunnelCounts();
});