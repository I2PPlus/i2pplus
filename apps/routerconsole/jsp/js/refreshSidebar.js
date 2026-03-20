/**
 * @module refreshSidebar
 * @description Manages the sidebar auto-refresh system for the I2P+ console.
 * Uses a SharedWorker for background fetches, applies differential DOM updates,
 * monitors connection status, and coordinates sidebar components (section toggles,
 * sticky positioning, new hosts, and mini graph).
 * @author dr|z3d
 * @license AGPLv3 or later
 */

import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { newHosts } from "/js/newHosts.js";
import { miniGraph } from "/js/miniGraph.js";

let alwaysUpdate = new Set();
let autoRefreshInterval = null;
let autoRefreshScheduled = false;
let connectionStatusTimeout;
let debounceTimeoutId = null;
let isDown = false;
let isPaused = false;
let isRefreshing = false;
let lastRefreshTime = 0;
let noResponse = 0;
let refreshActive = true;
let refreshTimeout = null;
let responseDoc = null;
let xhrContainer = document.getElementById("xhr");

const parser = new DOMParser();
const sb = document.querySelector("#sidebar");
const uri = location.pathname;
const worker = new SharedWorker("/js/fetchWorker.js");
const elements = { badges: [], volatileElements: [] };
const alwaysUpdateIds = ["lsCount", "sb_updateform", "sb_shutdownStatus"];

/**
 * Caches references to sidebar badge and volatile elements for efficient updates.
 * @function updateCachedElements
 * @returns {void}
 */
function updateCachedElements() {
  if (sb) {
    elements.badges = Array.from(sb.querySelectorAll(".badge:not(#newHosts), #tunnelCount, #newsCount"));
    elements.volatileElements = Array.from(sb.querySelectorAll(".volatile:not(.badge)"));
    xhrContainer = document.getElementById("xhr");
    const existingIds = new Set(elements.badges.map(badge => badge.id));
    alwaysUpdate = new Set(alwaysUpdateIds.filter(id => existingIds.has(id)));
  }
}

/**
 * Gets the configured refresh interval in milliseconds.
 * @function getRefreshInterval
 * @returns {number} The refresh interval in milliseconds
 */
function getRefreshInterval() {
  if (refresh != null) {
    return refresh * 1000;
  } else {
    return 3*1000;
  }
}

worker.port.start();
worker.port.addEventListener("message", ({ data }) => {
  try {
    const { responseText } = data;
    if (responseText) {
      noResponse = 0;
      if (responseText.includes("<body id=sb>")) {
        responseDoc = parser.parseFromString(responseText, "text/html");
        requestAnimationFrame(applySidebarUpdates);
      }
    } else {
      noResponse = Math.min(noResponse + 1, 10);
    }
  } catch {
    noResponse = Math.min(noResponse + 1, 10);
  }
  checkConnectionStatus();
});

/**
 * Initializes all sidebar components and starts auto-refresh.
 * @async
 * @function start
 * @returns {Promise<void>}
 */
async function start() {
  isSidebarVisible();
  updateCachedElements();
  sectionToggler();
  newHosts();
  countNewsItems();
  startAutoRefresh();
  checkConnectionStatus();
  window.addEventListener("resize", stickySidebar, { passive: true });
  stickySidebar();
}

/**
 * Starts the sidebar auto-refresh interval timer.
 * @function startAutoRefresh
 * @returns {void}
 */
function startAutoRefresh() {
  if (autoRefreshInterval) return;

  autoRefreshInterval = setInterval(() => {
    if (!document.hidden && navigator.onLine && refreshActive && !isRefreshing) {
      refreshSidebar();
    }
  }, getRefreshInterval());
}

/**
 * Stops the sidebar auto-refresh interval timer.
 * @function stopAutoRefresh
 * @returns {void}
 */
function stopAutoRefresh() {
  if (autoRefreshInterval) {
    clearInterval(autoRefreshInterval);
    autoRefreshInterval = null;
  }
}

/**
 * Triggers a sidebar refresh by posting a fetch request to the SharedWorker.
 * @async
 * @function refreshSidebar
 * @param {boolean} [force=false] - Whether to force the refresh regardless of rate limits
 * @returns {Promise<void>}
 */
export async function refreshSidebar(force = false) {
  if (!refreshActive || document.hidden || !navigator.onLine) return;
  try {
    worker.port.postMessage({ url: `/xhr1.jsp?requestURI=${uri}`, force });
  } catch (e) {
    noResponse = Math.min(noResponse + 1, 10);
  } finally {
    checkConnectionStatus();
    isRefreshing = false;
  }
}

/**
 * Applies differential updates from the fetched sidebar document to the current DOM.
 * @function applySidebarUpdates
 * @returns {void}
 */
function applySidebarUpdates() {
  xhrContainer = document.getElementById("xhr");
  if (!responseDoc || !xhrContainer) return;

  updateCachedElements();

  const responseElements = {
    volatileElements: Array.from(responseDoc.querySelectorAll(".volatile:not(.badge)")),
    badges: Array.from(responseDoc.querySelectorAll(".badge:not(#newHosts)")),
  };

  const volatile = xhrContainer.querySelectorAll(".volatile");
  const volatileResponse = responseDoc.querySelectorAll(".volatile");

  if (volatile.length !== volatileResponse.length) {
    return refreshAll();
  }

  const updates = [];
  elements.volatileElements.forEach((elem, i) => {
    const respElem = responseElements.volatileElements[i];
    if (!respElem) {
      requestAnimationFrame(checkConnectionStatus);
      return;
    }
    if (elem.classList.contains("statusDown") && elem.outerHTML !== respElem.outerHTML) {
      updates.push(() => {
        elem.outerHTML = respElem.outerHTML;
        refreshAll();
      });
    } else if (elem.innerHTML !== respElem.innerHTML) {
      updates.push(() => {
        elem.innerHTML = respElem.innerHTML;
      });
    }
  });

  elements.badges.forEach((elem, i) => {
    const respElem = responseElements.badges[i];
    if ((respElem && elem.textContent !== respElem.textContent) || alwaysUpdate.has(elem.id)) {
      updates.push(() => {
        if (respElem) {
          elem.textContent = respElem.textContent;
        }
      });
    }
  });

  if (updates.length > 0) {
    requestAnimationFrame(() => {
      updates.forEach(fn => fn());
      countNewsItems();
      newHosts();
      sectionToggler();
    });
  }

  isRefreshing = false;
  noResponse = 0;
}

/**
 * Performs a full sidebar refresh by replacing all innerHTML from the fetched document.
 * @function refreshAll
 * @returns {void}
 */
function refreshAll() {
  if (!responseDoc) {
    noResponse = Math.min(noResponse + 1, 10);
    return;
  }
  const sbResponse = responseDoc.getElementById("sb");
  if (!sbResponse) return;

  xhrContainer = document.getElementById("xhr");
  if (!xhrContainer) return;

  xhrContainer.innerHTML = sbResponse.innerHTML;
  updateCachedElements();
  sectionToggler();
  newHosts();
  countNewsItems();
  isDown = false;
  noResponse = 0;
  document.body.classList.remove("isDown");
  isRefreshing = false;
}

/**
 * Checks if the browser reports an online connection.
 * @async
 * @function isOnline
 * @returns {Promise<boolean>} True if online
 */
async function isOnline() {
  return navigator.onLine;
}

/**
 * Updates the connection status, adding/removing "isDown" class and triggering
 * appropriate refresh actions.
 * @async
 * @function updateConnectionStatus
 * @returns {Promise<void>}
 */
async function updateConnectionStatus() {
  clearTimeout(connectionStatusTimeout);
  connectionStatusTimeout = setTimeout(async() => {
    const online = await isOnline();
    const currentlyDown = noResponse > 3 || !online;
    if (currentlyDown && !isDown) {
      isDown = true;
      document.body.classList.add("isDown");
      refreshAll();
    } else if (!currentlyDown && isDown) {
      isDown = false;
      noResponse = 0;
      lastRefreshTime = 0;
      document.body.classList.remove("isDown");
      isRefreshing = true;
      refreshSidebar(true).finally(() => { isRefreshing = false; });
    }
  }, 500);
}

/**
 * Triggers a connection status check via the debounced update function.
 * @function checkConnectionStatus
 * @returns {void}
 */
function checkConnectionStatus() {
  updateConnectionStatus();
}

window.addEventListener("online", updateConnectionStatus);
window.addEventListener("offline", updateConnectionStatus);
setInterval(updateConnectionStatus, 15000);

window.addEventListener("message", (event) => {
  if (event.origin !== window.location.origin) return;
  if (event.data === "iframeLoaded" && !isRefreshing) {
    startAutoRefresh();
    isRefreshing = true;
  }
});

let observer;

/**
 * Sets up a MutationObserver on the xhr container to detect DOM changes
 * and trigger sidebar refreshes.
 * @function isSidebarVisible
 * @returns {void}
 */
function isSidebarVisible() {
  const target = document.getElementById("xhr");
  if (!target) return;

  observer = new MutationObserver(() => {
    if (document.hidden || isRefreshing) {
      observer.disconnect();
      return;
    }
    clearTimeout(debounceTimeoutId);
    debounceTimeoutId = setTimeout(() => {
      isRefreshing = true;
      refreshSidebar(true).finally(() => {
        isRefreshing = false;
        observer.observe(target, { childList: true, subtree: true });
      });
    }, getRefreshInterval());
  });

  observer.observe(target, { childList: true, subtree: true });
}

/**
 * Handles online/offline/visibility events by triggering a forced sidebar refresh.
 * @function handleStatus
 * @returns {void}
 */
function handleStatus() {
  if (document.hidden || isRefreshing) return;
  isRefreshing = true;
  refreshSidebar(true).finally(() => {
    isRefreshing = false;
  });
}

/**
 * Polls for the xhr container element and initializes the sidebar when available.
 * @function initSidebar
 * @returns {void}
 */
function initSidebar() {
  const interval = setInterval(() => {
    if (xhrContainer) {
      clearInterval(interval);
      start();
    }
  }, 5);
}

initSidebar();

window.addEventListener("online", handleStatus);
window.addEventListener("offline", checkConnectionStatus);
document.addEventListener("visibilitychange", handleStatus);