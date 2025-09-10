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
  return refresh * 1000;
}

worker.port.start();
worker.port.addEventListener("message", ({ data }) => {
  try {
    const { responseText } = data;
    if (responseText) {
      responseDoc = parser.parseFromString(responseText, "text/html");
      requestAnimationFrame(startAutoRefresh);
      noResponse = 0;
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
  newHosts();
  countNewsItems();
  sectionToggler();
  startAutoRefresh();
  checkConnectionStatus();
  window.addEventListener("resize", stickySidebar, { passive: true });
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
  const interval = getRefreshInterval();
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
    if (!responseDoc || !xhrContainer) {
      noResponse = Math.min(noResponse + 1, 10);
      return;
    }
    const responseElements = {
      volatileElements: Array.from(responseDoc.querySelectorAll(".volatile:not(.badge)")),
      badges: Array.from(responseDoc.querySelectorAll(".badge:not(#newHosts)")),
    };
    const volatile = xhrContainer.querySelectorAll(".volatile");
    const volatileResponse = responseDoc.querySelectorAll(".volatile");
    if (volatile.length !== volatileResponse.length) return refreshAll();
    const updates = [];
    elements.volatileElements.forEach((elem, i) => {
      const respElem = responseElements.volatileElements[i];
      if (!respElem) return;
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
    noResponse = 0;
  } catch {
    noResponse = Math.min(noResponse + 1, 10);
  } finally {
    checkConnectionStatus();
    startAutoRefresh();
  }
}

function refreshAll() {
  if (!sb || !responseDoc) return noResponse = Math.min(noResponse + 1, 10);
  updateCachedElements();
  const sbResponse = responseDoc.getElementById("sb");
  if (sbResponse && xhrContainer && sb.innerHTML !== sbResponse.innerHTML) {
    xhrContainer.innerHTML = sbResponse.innerHTML;
    sectionToggler();
  }
  noResponse = 0;
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
      document.body.classList.remove("isDown");
      refreshSidebar(true);
      scheduleNextAutoRefresh();
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
  if (event.data === "iframeLoaded") {
    startAutoRefresh();
  }
});

function isSidebarVisible() {
  const target = document.getElementById("xhr");
  if (!target) return;
  const observer = new MutationObserver(() => {
    if (document.hidden) return;
    const now = Date.now();
    const elapsed = now - lastRefreshTime;
    const interval = getRefreshInterval();
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
  });
  observer.observe(target, {
    childList: true,
    subtree: true,
  });
}

function handleStatus() {
  lastRefreshTime = 0;
  if (!document.hidden) {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        refreshSidebar(true);
      });
    });
  }
  startAutoRefresh();
}

window.addEventListener("online", handleStatus);
window.addEventListener("offline", checkConnectionStatus);
document.addEventListener("visibilitychange", handleStatus);
document.addEventListener("DOMContentLoaded", start);