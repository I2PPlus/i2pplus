/**
 * @module refreshSidebar
 * @description Manages the sidebar auto-refresh system for the I2P+ console.
 * Uses a SharedWorker for background fetches, applies differential DOM updates,
 * monitors connection status, and coordinates sidebar components (section toggles,
 * sticky positioning, new hosts, and mini graph). Consumes the sidebar response
 * prefetched at parse time by refreshKick.js so the refresh cadence is
 * independent of page load.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { newHosts } from "/js/newHosts.js";

/** @type {number} */
const REQUEST_TIMEOUT = 15000;

let alwaysUpdate = new Set();
let autoRefreshInterval = null;
let connectionStatusTimeout;
let rAFPending = false;
let isDown = false;
let isRefreshing = false;
let recoveryPending = false;
let lastRefreshTime = 0;
let lastRequestTime = 0;
let noResponse = 0;
let responseDoc = null;
let xhrContainer = document.getElementById("xhr");
let statusIntervalId = setInterval(updateConnectionStatus, 15000);

const parser = new DOMParser();
const sb = document.querySelector("#sidebar");
const uri = location.pathname;
const worker = new SharedWorker("/js/fetchWorker.js");
const elements = { badges: [], volatileElements: [] };
const alwaysUpdateIds = ["lsCount"];

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
    if (responseText && responseText.includes("<body id=sb>")) {
      noResponse = 0;
      responseDoc = parser.parseFromString(responseText, "text/html");
      // Skip applying while the tab is hidden: rAF is suspended in hidden
      // tabs, and the stale response is replaced by a fresh fetch on regain.
      if (!document.hidden && !rAFPending) {
        rAFPending = true;
        requestAnimationFrame(() => {
          rAFPending = false;
          if (document.hidden) {return;}
          applySidebarUpdates();
        });
      }
    } else {
      noResponse = Math.min(noResponse + 1, 10);
      isRefreshing = false;
    }
  } catch {
    noResponse = Math.min(noResponse + 1, 10);
    isRefreshing = false;
  }
  checkConnectionStatus();
});

/**
 * Initializes all sidebar components and starts auto-refresh.
 * @function start
 * @returns {void}
 */
function start() {
  updateCachedElements();
  sectionToggler();
  newHosts();
  countNewsItems();
  startAutoRefresh();
  checkConnectionStatus();
  window.addEventListener("resize", stickySidebar, { passive: true });
  stickySidebar();
  consumeKick();
}

/**
 * Hands off from the parse-time kicker (refreshKick.js): stops its
 * refreshElements loop. The module's own interval cadence takes over,
 * so the sidebar values shown at load stay current.
 * @function consumeKick
 * @returns {void}
 */
function consumeKick() {
  const kick = window.__i2pSidebarKick;
  delete window.__i2pSidebarKick;
  if (kick && typeof kick.stop === "function") {kick.stop();}
}

/**
 * Starts the sidebar auto-refresh interval timer.
 * @function startAutoRefresh
 * @returns {void}
 */
function startAutoRefresh() {
  if (autoRefreshInterval) {return;}

  autoRefreshInterval = setInterval(() => {
    if (!document.hidden && navigator.onLine && !isDown && !isRefreshing) {
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
 * Sets the in-flight guard for the duration of the request; the guard is
 * released when the response is applied (or when the request fails), and a
 * watchdog in updateConnectionStatus clears it if no response arrives within
 * REQUEST_TIMEOUT.
 * @function refreshSidebar
 * @param {boolean} [force=false] - Whether to force the refresh regardless of rate limits
 * @returns {void}
 */
function refreshSidebar(force = false) {
  if (document.hidden || !navigator.onLine) {return;}
  if (!force && isDown) {return;}
  if (isRefreshing) {return;}
  isRefreshing = true;
  lastRequestTime = Date.now();
  try {
    worker.port.postMessage({ url: `/xhr1.jsp?requestURI=${uri}`, force });
  } catch (e) {
    noResponse = Math.min(noResponse + 1, 10);
    console.warn("refreshSidebar: postMessage failed", e);
    isRefreshing = false;
  } finally {
    checkConnectionStatus();
  }
}

/**
 * Applies differential updates from the fetched sidebar document to the current DOM.
 * @function applySidebarUpdates
 * @returns {void}
 */
function applySidebarUpdates() {
  xhrContainer = document.getElementById("xhr");
  if (!responseDoc || !xhrContainer) {
    isRefreshing = false;
    return;
  }
  if (isDown && !recoveryPending) {
    isRefreshing = false;
    return;
  }

  updateCachedElements();

  const volatileResponse = Array.from(responseDoc.querySelectorAll(".volatile"));
  const responseElements = {
    volatileElements: volatileResponse.filter(el => !el.classList.contains("badge")),
    badges: Array.from(responseDoc.querySelectorAll(".badge:not(#newHosts)")),
  };

  const volatile = xhrContainer.querySelectorAll(".volatile");

  if (volatile.length !== volatileResponse.length) {
    return refreshAll();
  }

  const responseById = new Map();
  responseElements.volatileElements.forEach(el => {
    if (el.id) {responseById.set(el.id, el);}
  });

  const updates = [];
  let needsFullRefresh = false;
  elements.volatileElements.forEach((elem, i) => {
    const respElem = (elem.id && responseById.get(elem.id)) || responseElements.volatileElements[i];
    if (!respElem) {
      requestAnimationFrame(checkConnectionStatus);
      return;
    }
    if (elem.classList.contains("statusDown") && elem.outerHTML !== respElem.outerHTML) {
      needsFullRefresh = true;
    } else if (elem.innerHTML !== respElem.innerHTML) {
      updates.push(() => {
        elem.innerHTML = respElem.innerHTML;
      });
    }
  });

  if (needsFullRefresh) {
    requestAnimationFrame(() => {
      refreshAll();
      syncGraphDataAttributes();
    });
    isRefreshing = false;
    noResponse = 0;
    return;
  }

  const badgesById = new Map();
  responseElements.badges.forEach(el => {
    if (el.id) {badgesById.set(el.id, el);}
  });

  elements.badges.forEach((elem, i) => {
    const respElem = (elem.id && badgesById.get(elem.id)) || responseElements.badges[i];
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
      updatePersistentBars(responseDoc);
    });
  }

  // Sync minigraph canvas data attributes from response
  syncGraphDataAttributes();

  isRefreshing = false;
  noResponse = 0;
  lastRefreshTime = Date.now();

  if (recoveryPending) {
    recoveryPending = false;
    isDown = false;
    document.body.classList.remove("isDown");
  }
}

/**
 * Updates non-volatile memory and CPU bars from the response document.
 * The bars persist in the DOM (no volatile class), so style.width changes
 * trigger CSS transition for smooth animation.
 * @function updatePersistentBars
 * @param {Document} responseDoc - The parsed XHR response document
 * @returns {void}
 */
function updatePersistentBars(responseDoc) {
  ["sb_memoryBar", "sb_CPUBar"].forEach(function(id) {
    const r = responseDoc.querySelector("#" + id + " .percentBarInner");
    const d = document.querySelector("#" + id + " .percentBarInner");
    if (r && d && r.style.width !== d.style.width) {d.style.width = r.style.width;}
    const rt = responseDoc.querySelector("#" + id + " .percentBarText");
    const dt = document.querySelector("#" + id + " .percentBarText");
    if (rt && dt && rt.textContent !== dt.textContent) {dt.textContent = rt.textContent;}
  });
}

/**
 * Copies data-rx/data-tx attributes from the response canvas to the live canvas,
 * so the minigraph can re-render with fresh data after differential updates.
 * @function syncGraphDataAttributes
 * @returns {void}
 */
function syncGraphDataAttributes() {
  const canvas = document.getElementById("minigraph");
  const respCanvas = responseDoc && responseDoc.getElementById("minigraph");
  if (!canvas || !respCanvas) {return;}
  const rx = respCanvas.getAttribute("data-rx");
  const tx = respCanvas.getAttribute("data-tx");
  if (rx) {canvas.setAttribute("data-rx", rx);}
  if (tx) {canvas.setAttribute("data-tx", tx);}
  if (window.renderNewGraph) {window.renderNewGraph();}
}

/**
 * Performs a full sidebar refresh by replacing all innerHTML from the fetched document.
 * @function refreshAll
 * @returns {void}
 */
function refreshAll() {
  if (isDown && !recoveryPending) {
    isRefreshing = false;
    return;
  }
  if (!responseDoc) {
    noResponse = Math.min(noResponse + 1, 10);
    isRefreshing = false;
    return;
  }
  const sbResponse = responseDoc.getElementById("sb");
  if (!sbResponse) {
    isRefreshing = false;
    return;
  }

  xhrContainer = document.getElementById("xhr");
  if (!xhrContainer) {
    isRefreshing = false;
    return;
  }

  xhrContainer.innerHTML = sbResponse.innerHTML;
  updateCachedElements();
  sectionToggler();
  newHosts();
  countNewsItems();
  isRefreshing = false;
  lastRefreshTime = Date.now();
  // Trigger immediate minigraph re-render after full sidebar replacement
  document.dispatchEvent(new Event("sidebarRefreshed"));

  if (recoveryPending) {
    recoveryPending = false;
    isDown = false;
    document.body.classList.remove("isDown");
  }
}

/**
 * Checks if the browser reports an online connection.
 * @function isOnline
 * @returns {boolean} True if online
 */
function isOnline() {
  return navigator.onLine;
}

/**
 * Updates the connection status, adding/removing "isDown" class and triggering
 * appropriate refresh actions.
 * @function updateConnectionStatus
 * @returns {void}
 */
function updateConnectionStatus() {
  clearTimeout(connectionStatusTimeout);
  connectionStatusTimeout = setTimeout(() => {
    const online = isOnline();
    // Watchdog: a request with no response within REQUEST_TIMEOUT (hung
    // fetch, dead worker) would otherwise pin the in-flight guard forever.
    if (isRefreshing && Date.now() - lastRequestTime > REQUEST_TIMEOUT) {
      isRefreshing = false;
    }
    const currentlyDown = noResponse > 3 || !online;
    if (currentlyDown && !isDown) {
      isDown = true;
      document.body.classList.add("isDown");
    }
    if (isDown) {
      refreshSidebar(true);
    }
    if (!currentlyDown && isDown && !recoveryPending) {
      recoveryPending = true;
      setTimeout(() => {
        if (recoveryPending) {
          recoveryPending = false;
          isRefreshing = false;
        }
      }, 30000);
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

window.addEventListener("offline", updateConnectionStatus);

/**
 * Handles online/offline/visibility events by triggering a forced sidebar refresh.
 * While the tab is hidden, all refresh activity is paused (refresh interval and
 * status interval) so the browser never accumulates throttled timer events to
 * replay on regain. On regain, the in-flight guard is released and any stale
 * response discarded before refreshing with fresh data.
 * @function handleStatus
 * @returns {void}
 */
function handleStatus() {
  if (document.hidden) {
    stopAutoRefresh();
    clearTimeout(connectionStatusTimeout);
    clearInterval(statusIntervalId);
    statusIntervalId = null;
    return;
  }
  isRefreshing = false;
  rAFPending = false;
  responseDoc = null;
  startAutoRefresh();
  if (!statusIntervalId) {
    statusIntervalId = setInterval(updateConnectionStatus, 15000);
  }
  refreshSidebar(true);
  checkConnectionStatus();
}

/**
 * Initializes the sidebar when the xhr container is available, with a bounded
 * retry for the (rare) case the container is inserted after script execution.
 * @function initSidebar
 * @returns {void}
 */
function initSidebar() {
  if (xhrContainer) {
    start();
    return;
  }
  let attempts = 0;
  const interval = setInterval(() => {
    if (xhrContainer) {
      clearInterval(interval);
      start();
    } else if (++attempts >= 50) {
      clearInterval(interval);
    }
  }, 100);
}

initSidebar();

window.addEventListener("online", handleStatus);
document.addEventListener("visibilitychange", handleStatus);
