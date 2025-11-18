/* I2P+ refreshElements.js by dr|z3d */
/* Refresh elements via fetch using a background worker */
/* Usage: refreshElement("target selectors", "target url", refresh interval (ms)) */
/* License: AGPL3 or later */

import morphdom from "/js/morphdom.js";

let refreshIntervalId = null;
let isRefreshing = false;
let currentTargetSelector = null;
let currentUrl = null;

const fetchWorker = new SharedWorker("/js/fetchWorker.js");
fetchWorker.port.start();

fetchWorker.port.onmessage = function(e) {
  const { responseText } = e.data;
  if (!responseText || !currentUrl) return;

  const parser = new DOMParser();
  const doc = parser.parseFromString(responseText, "text/html");

  requestAnimationFrame(() => {
    const targetElements = document.querySelectorAll(currentTargetSelector);
    const targetElementsResponse = doc.querySelectorAll(currentTargetSelector);

    targetElements.forEach((targetElement, index) => {
      const targetElementResponse = targetElementsResponse[index];
      if (targetElement && targetElementResponse) {
        morphdom(targetElement, targetElementResponse);
      }
    });

    document.dispatchEvent(new Event("refreshComplete"));
  });
};

export function refreshElements(targetSelector, url, delay) {
  currentTargetSelector = targetSelector;
  currentUrl = url;

  function refresh() {
    if (document.visibilityState !== "visible" || isRefreshing) return;

    isRefreshing = true;
    progressx.show(theme);
    progressx.progress(0.5);

    fetchWorker.port.postMessage({ url: currentUrl });

    setTimeout(() => {
      progressx.hide();
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