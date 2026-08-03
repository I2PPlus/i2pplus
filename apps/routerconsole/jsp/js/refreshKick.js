/**
 * @module refreshKick
 * @description Keeps the sidebar current while refreshSidebar.js loads by
 * starting the volatile refresh on the configured interval at parse time.
 * Runs as a classic script from sidebar_noframe.jsi, immediately after the
 * sidebar markup; delegates the volatile refresh to refreshElements.js,
 * then hands off to refreshSidebar.js.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

(function () {
  "use strict";
  // Defensive: the script is included once per document
  if (window.__i2pSidebarKick) {return;}
  const kick = {stop: null};
  window.__i2pSidebarKick = kick;
  const url = "/xhr1.jsp?requestURI=" + encodeURIComponent(location.pathname);
  const delay = refresh != null ? Math.max(refresh * 1000, 1000) : 3000;
  import("/js/refreshElements.js").then((m) => {
    // The module already consumed the kick and took over; don't start
    if (window.__i2pSidebarKick !== kick) {return;}
    kick.stop = m.refreshElements(".volatile", url, delay, false, true);
  }).catch(() => {});
})();
