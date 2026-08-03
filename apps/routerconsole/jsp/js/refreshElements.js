/**
 * @module refreshElements
 * @description Refreshes DOM elements via fetch using a SharedWorker for background
 * requests. Uses morphdom for efficient DOM diffing and supports visibility-based
 * refresh scheduling.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

import morphdom from "/js/morphdom.js";

let refreshIntervalId = null;
let isRefreshing = false;
let currentTargetSelector = null;
let currentUrl = null;
let visibilityHandler = null;

const fetchWorker = new SharedWorker("/js/fetchWorker.js");
fetchWorker.port.start();

fetchWorker.port.onmessage = function(e) {
  const { responseText } = e.data;
  if (!responseText || !currentUrl) { return; }

  const parser = new DOMParser();
  const doc = parser.parseFromString(responseText, "text/html");

  requestAnimationFrame(() => {
    currentTargetSelector.forEach(selector => {
      const targetElements = document.querySelectorAll(selector);
      const targetElementsResponse = doc.querySelectorAll(selector);

      targetElements.forEach((targetElement, index) => {
        const targetElementResponse = targetElementsResponse[index];
        if (targetElement && targetElementResponse) {
          morphdom(targetElement, targetElementResponse, {
            onBeforeElUpdated: (fromEl, toEl) => {
              if (fromEl.isEqualNode(toEl)) {return false;}
              return true;
            }
          });
        }
      });
    });

    document.dispatchEvent(new Event("refreshComplete"));
    document.dispatchEvent(new CustomEvent("elementsRefreshed", { detail: { selectors: currentTargetSelector } }));

  });
};

/**
 * Sets up periodic element refresh using a SharedWorker for fetch requests.
 * Automatically pauses when the document is hidden and resumes on visibility.
 * @function refreshElements
 * @param {string|string[]} targetSelectors - CSS selector(s) for elements to refresh
 * @param {string} url - The URL to fetch content from
 * @param {number} delay - The refresh interval in milliseconds
 * @param {boolean} [immediate=false] - Fetch right away on setup, or wait for the first interval tick
 * @param {boolean} [silent=false] - Skip the progress bar on each refresh
 * @returns {Function} The stop function that halts the refresh loop
 * @example refreshElements("#sidebar", "/sidebar", 10000)
 * @example refreshElements(["#peers", "#status"], "/peers", 5000)
 */
export function refreshElements(targetSelectors, url, delay, immediate = false, silent = false) {
  let selectors = [];

  if (typeof targetSelectors === "string") {
    selectors = targetSelectors.split(",").map(s => s.trim());
  } else if (Array.isArray(targetSelectors)) {
    selectors = targetSelectors.map(s => s.trim());
  }

  currentTargetSelector = selectors;
  currentUrl = url;

  let instanceIntervalId = null;

  function refresh() {
    if (document.visibilityState !== "visible" || isRefreshing) { return; }

    isRefreshing = true;
    if (!silent) { progressx?.show(theme); }

    fetchWorker.port.postMessage({ url: currentUrl });

    setTimeout(() => {
      if (!silent) { progressx?.hide(); }
      isRefreshing = false;
    }, 1000);

  }

  if (refreshIntervalId) {
    clearInterval(refreshIntervalId);
  }

  if (document.visibilityState === "visible") {
    if (immediate) { refresh(); }
    instanceIntervalId = setInterval(refresh, delay);
    refreshIntervalId = instanceIntervalId;
  }

  function handleVisibilityChange() {
    if (document.visibilityState === "visible") {
      refresh();
      if (!instanceIntervalId) {
        instanceIntervalId = setInterval(refresh, delay);
        refreshIntervalId = instanceIntervalId;
      }
    } else {
      clearInterval(instanceIntervalId);
      instanceIntervalId = null;
      refreshIntervalId = null;
      isRefreshing = false;
    }
  }

  if (visibilityHandler) {
    document.removeEventListener("visibilitychange", visibilityHandler);
  }
  visibilityHandler = handleVisibilityChange;
  document.addEventListener("visibilitychange", visibilityHandler);

  /**
   * Halts the refresh loop for this instance. Does nothing if another
   * refreshElements call has since taken over the singleton state.
   * @function stop
   * @returns {void}
   */
  function stop() {
    if (visibilityHandler !== handleVisibilityChange) { return; }
    document.removeEventListener("visibilitychange", handleVisibilityChange);
    visibilityHandler = null;
    clearInterval(instanceIntervalId);
    instanceIntervalId = null;
    refreshIntervalId = null;
    currentUrl = null;
    currentTargetSelector = null;
    isRefreshing = false;
  }

  return stop;
}
