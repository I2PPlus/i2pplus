/* I2P+ detectPageZoom.js by dr|z3d */
/* Apply theme modifications when pagezoom is active */
/* License: AGPLv3 or later */

document.addEventListener("DOMContentLoaded", () => {
  if (theme === "dark") {
    const manageZoomCSS = () => {
      const doc = document.documentElement;
      const zoom = Math.round(window.devicePixelRatio * 100);
      const zoomCss = document.getElementById("zoomCss");
      if (zoom !== 100) {
        if (!zoomCss) {
          const link = document.createElement("link");
          link.id = "zoomCss";
          link.rel = "stylesheet";
          link.href = `/themes/console/${theme}/zoom.css?t=${Date.now()}`;
          document.body.appendChild(link);
          if (!doc.classList.contains("zoomed")) {doc.classList.add("zoomed");}
        }
      } else if (zoomCss) {
        document.body.removeChild(zoomCss);
        doc.classList.remove("zoomed");
      }
    };
    setTimeout(manageZoomCSS, 500);
    window.addEventListener("resize", manageZoomCSS);
    new MutationObserver(manageZoomCSS).observe(document.body, { childList: true, subtree: true });
  }
});