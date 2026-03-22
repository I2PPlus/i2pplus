/**
 * @module refreshElements
 * @description Refreshes DOM elements via fetch using a SharedWorker for background
 * requests. Uses morphdom for efficient DOM diffing and supports visibility-based
 * refresh scheduling.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import morphdom from "/js/morphdom.js";

let refreshIntervalId = null;
let isRefreshing = false;
let currentTargetSelector = null;
let currentUrl = null;

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
 * @returns {void}
 * @example refreshElements("#sidebar", "/sidebar", 10000)
 * @example refreshElements(["#peers", "#status"], "/peers", 5000)
 */
export function refreshElements(targetSelectors, url, delay) {
  let selectors = [];

  if (typeof targetSelectors === "string") {
    selectors = targetSelectors.split(",").map(s => s.trim());
  } else if (Array.isArray(targetSelectors)) {
    selectors = targetSelectors.map(s => s.trim());
  }

  currentTargetSelector = selectors;
  currentUrl = url;

  function refresh() {
    if (document.visibilityState !== "visible" || isRefreshing) { return; }

    isRefreshing = true;
    progressx?.show(theme);

    fetchWorker.port.postMessage({ url: currentUrl });

    setTimeout(() => {
      progressx?.hide();
      isRefreshing = false;
    }, 1000);

  }

  if (refreshIntervalId) {
    clearInterval(refreshIntervalId);
  }

  if (document.visibilityState === "visible") {
    refresh();
    refreshIntervalId = setInterval(refresh, delay);
  }

  function handleVisibilityChange() {
    if (document.visibilityState === "visible") {
      refresh();
      if (!refreshIntervalId) {
        refreshIntervalId = setInterval(refresh, delay);
      }
    } else {
      clearInterval(refreshIntervalId);
      refreshIntervalId = null;
      isRefreshing = false;
    }
  }

  document.removeEventListener("visibilitychange", handleVisibilityChange);
  document.addEventListener("visibilitychange", handleVisibilityChange);
}