/**
 * @module detectPageZoom
 * @description Detects browser page zoom level changes and applies/removes
 * a zoom-specific CSS file when using the dark theme.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

document.addEventListener("DOMContentLoaded", () => {
  if (theme === "dark") {
    /**
     * Manages the zoom CSS by detecting the device pixel ratio and
     * dynamically loading/removing the zoom stylesheet.
     * @function manageZoomCSS
     * @returns {void}
     */
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