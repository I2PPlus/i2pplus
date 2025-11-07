/* I2P+ RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { newHosts } from "/js/newHosts.js";
import { miniGraph } from "/js/miniGraph.js";

let alwaysUpdate = new Set();
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

function updateCachedElements() {
  if (sb) {
    elements.badges = Array.from(sb.querySelectorAll(".badge:not(#newHosts), #tunnelCount, #newsCount"));
    elements.volatileElements = Array.from(sb.querySelectorAll(".volatile:not(.badge)"));
    xhrContainer = document.getElementById("xhr");
    const existingIds = new Set(elements.badges.map(badge => badge.id));
    alwaysUpdate = new Set(alwaysUpdateIds.filter(id => existingIds.has(id)));
  }
}

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

function startAutoRefresh() {
  if (autoRefreshScheduled) return;
  autoRefreshScheduled = true;
  setTimeout(() => {
    autoRefreshScheduled = false;
    scheduleNextAutoRefresh();
  }, 200);
}

function scheduleNextAutoRefresh() {
  clearTimeout(refreshTimeout);
  const now = Date.now();
  let interval = getRefreshInterval();
  if (lastRefreshTime === 0) {
    lastRefreshTime = now;
  } else {
    const elapsed = now - lastRefreshTime;
    const timeUntilNext = interval - (elapsed % interval);
    refreshTimeout = setTimeout(() => {
      lastRefreshTime = Date.now();
      refreshSidebar();
    }, timeUntilNext);
    return;
  }
  lastRefreshTime = now;
  refreshTimeout = setTimeout(refreshSidebar, interval);
}

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

async function isOnline() {
  return navigator.onLine;
}

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
        refreshSidebar(true).finally(() => {
        isRefreshing = false;
      });
    }
  }, 500);
}

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

function isSidebarVisible() {
  const target = document.getElementById("xhr");
  if (!target) return;
  const observer = new MutationObserver(() => {
    if (document.hidden || isRefreshing) return;
    debounceTimeoutId = setTimeout(() => {
      const now = Date.now();
      const elapsed = now - lastRefreshTime;
      let interval = getRefreshInterval();
      if (elapsed < interval) {
        if (debounceTimeoutId) clearTimeout(debounceTimeoutId);
        debounceTimeoutId = setTimeout(() => {
          lastRefreshTime = Date.now();
          requestAnimationFrame(() => {
            requestAnimationFrame(() => {
              refreshSidebar(true);
            });
          });
          scheduleNextAutoRefresh();
        }, interval - elapsed);
      } else {
        lastRefreshTime = now;
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            refreshSidebar(true);
          });
        });
        scheduleNextAutoRefresh();
      }
    }, 100);
  });
  observer.observe(target, {
    childList: true,
    subtree: true,
  });
}

function handleStatus() {
  lastRefreshTime = 0;
  if (!document.hidden && !isRefreshing) {
    isRefreshing = true;
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        refreshSidebar(true);
      });
    });
  }
  startAutoRefresh();
}

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