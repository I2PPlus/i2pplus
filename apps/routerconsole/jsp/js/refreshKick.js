/**
 * @module refreshKick
 * @description Prefetches the sidebar XHR response at parse time so the
 * sidebar refresh is independent of page load. Runs as a classic (non-module)
 * script from sidebar_noframe.jsi, immediately after the sidebar markup.
 * Fetches /xhr1.jsp right away, caches the response on window.__i2pSidebarKick,
 * and keeps re-fetching on the refresh interval until the refreshSidebar.js
 * module consumes the cache at module boot, then stops itself. Responses
 * arriving after the module has consumed the cache are discarded.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

(function () {
  "use strict";

  const KEY = "__i2pSidebarKick";

  // Defensive: the script is included once per document
  if (window[KEY]) {return;}

  const kick = {done: false, text: ""};
  window[KEY] = kick;

  const uri = location.pathname;
  let interval = null;

  /** @type {number} Fetch timeout in ms, matching fetchWorker FETCH_TIMEOUT. */
  const FETCH_TIMEOUT = 10000;

  /**
   * Builds the xhr1 request URL for the current page.
   * @function buildUrl
   * @returns {string} The request URL
   */
  function buildUrl() {
    return "/xhr1.jsp?requestURI=" + encodeURIComponent(uri);
  }

  /**
   * Fetches the latest sidebar markup, replacing the cached response.
   * Stops the loop once the module consumes (deletes) the cache key;
   * responses arriving after that are discarded.
   * @function tick
   * @returns {void}
   */
  function tick() {
    if (window[KEY] !== kick) {
      clearInterval(interval);
      return;
    }
    const controller = new AbortController();
    const timeoutId = setTimeout(() => {controller.abort();}, FETCH_TIMEOUT);
    fetch(buildUrl(), {signal: controller.signal})
      .then((r) => (r.ok ? r.text() : Promise.reject(new Error("HTTP " + r.status))))
      .then((text) => {
        if (window[KEY] !== kick || !text.includes("<body id=sb>")) {return;}
        kick.done = true;
        kick.text = text;
      })
      .catch(() => {})
      .finally(() => {clearTimeout(timeoutId);});
  }

  tick();
  interval = setInterval(tick, refresh != null ? Math.max(refresh * 1000, 1000) : 3000);
})();
