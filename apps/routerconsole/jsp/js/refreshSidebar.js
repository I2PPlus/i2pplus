/* I2P+ RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { newHosts } from "/js/newHosts.js";
import { miniGraph } from "/js/miniGraph.js";

let refreshActive = true;
let isPaused = false;
let isDown = false;
let noResponse = 0;
let refreshTimeout = null;
let responseDoc = null;
let lastRefreshTime = 0;
let isRefreshing = false;
let xhrContainer = document.getElementById("xhr");
const parser = new DOMParser();
const sb = document.querySelector("#sidebar");
const uri = location.pathname;
const worker = new SharedWorker("/js/fetchWorker.js");
let elements = {
  badges: sb ? Array.from(sb.querySelectorAll(".badge:not(#newHosts), #tunnelCount, #newsCount")) : [],
  volatileElements: sb ? Array.from(sb.querySelectorAll(".volatile:not(.badge)")) : [],
};

function updateCachedElements() {
  elements.badges = sb ? Array.from(sb.querySelectorAll(".badge:not(#newHosts), #tunnelCount, #newsCount")) : [];
  elements.volatileElements = sb ? Array.from(sb.querySelectorAll(".volatile:not(.badge)")) : [];
  xhrContainer = document.getElementById("xhr");
}

function initSidebar() {
  const setup = () => {
    sectionToggler();
    stickySidebar();
    miniGraph();
  };
  document.addEventListener("DOMContentLoaded", setup);
  window.addEventListener("resize", stickySidebar, { passive: true });
}

function getRefreshInterval() { return refresh * 1000; }

worker.port.start();
worker.port.addEventListener("message", ({ data }) => {
  try {
    const { responseText } = data;
    if (responseText) {
      responseDoc = parser.parseFromString(responseText, "text/html");
      requestAnimationFrame(startAutoRefresh);
      noResponse = 0;
    } else {noResponse++;}
  } catch {noResponse++;}
  checkConnectionStatus();
});

async function start() {
  checkConnectionStatus();
  sectionToggler();
  countNewsItems();
  handleFormSubmit();
  startAutoRefresh();
}

export function startAutoRefresh() {
  clearTimeout(refreshTimeout);
  const now = Date.now();
  const interval = getRefreshInterval();
  if (lastRefreshTime === 0) {
    lastRefreshTime = now;
  } else {
    const timeSinceLast = now - lastRefreshTime;
    const timeUntilNext = interval - (timeSinceLast % interval);
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
    if (!responseDoc) return noResponse++;
    if (!xhrContainer) return;
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
        updates.push(() => (elem.innerHTML = respElem.innerHTML));
      }
    });
    elements.badges.forEach((elem, i) => {
      const respElem = responseElements.badges[i];
      if ((respElem && elem.textContent !== respElem.textContent) ||
          elem.id === "lsCount" ||
          elem.id === "sb_updateform" ||
          elem.id === "sb_shutdownStatus") {
        updates.push(() => (elem.textContent = respElem?.textContent));
      }
    });
    requestAnimationFrame(() => {
      updates.forEach((fn) => fn());
      countNewsItems();
      newHosts();
      sectionToggler();
      updates.length = 0;
    });
    noResponse = 0;
  } catch {
    noResponse++;
    startAutoRefresh();
  } finally { checkConnectionStatus(); }
}

function refreshAll() {
  if (!(sb && responseDoc)) return noResponse++;
  updateCachedElements();
  const sbResponse = responseDoc.getElementById("sb");
  if (sbResponse && xhrContainer && sb.innerHTML !== sbResponse.innerHTML) {
    xhrContainer.innerHTML = sbResponse.innerHTML;
    sectionToggler();
  }
  noResponse = 0;
}

function handleFormSubmit() {
  document.addEventListener("submit", (event) => {
    const form = event.target.closest("form"),
      clickTarget = event.submitter;
    if (!form || !clickTarget) return;
    const formId = form.id, iframe = document.getElementById("processSidebarForm");
    iframe?.addEventListener("load", () => {
        const formResponse = responseDoc?.querySelector(`#${formId}`);
        if (!formResponse) return;
        if (form.id !== "form_sidebar") {
          updateCachedElements();
          form.innerHTML = formResponse.innerHTML;
          form.classList.add("activated");
          const shutdownNotice = document.getElementById("sb_shutdownStatus");
          const shutdownNoticeHR = sb?.querySelector("#sb_shutdownStatus+hr");
          const shutdownNoticeResponse = responseDoc?.getElementById("sb_shutdownStatus");
          if (shutdownNotice && shutdownNoticeResponse) {
            if (shutdownNoticeResponse.classList.contains("inactive")) {
              shutdownNotice.hidden = true;
              shutdownNoticeHR.hidden = true;
            } else if (shutdownNotice.innerHTML !== shutdownNoticeResponse.innerHTML) {
              shutdownNotice.hidden = false;
              shutdownNoticeHR.hidden = false;
              shutdownNotice.outerHTML = shutdownNoticeResponse.outerHTML;
            }
          }
          const updateForm = document.getElementById("sb_updateform");
          const updateFormResponse = responseDoc?.getElementById("sb_updateform");
          if (updateForm && updateFormResponse) {
            if (updateFormResponse.classList.contains("inactive")) {
              updateForm.hidden = true;
            } else if (updateForm.innerHTML !== updateFormResponse.innerHTML) {
              updateForm.outerHTML = updateFormResponse.outerHTML;
            }
          }
          if (form.id === "sb_routerControl") {
            const tunnelStatus = document.getElementById("sb_tunnelstatus");
            const tunnelStatusResponse = responseDoc?.getElementById("sb_tunnelstatus");
            if (tunnelStatus && tunnelStatusResponse && tunnelStatus.innerHTML !== tunnelStatusResponse.innerHTML) {
              tunnelStatus.outerHTML = tunnelStatusResponse.outerHTML;
            }
          }
          form.querySelectorAll("button").forEach((btn) => {
            btn.style.opacity = ".5";
            btn.style.pointerEvents = "none";
          });
        } else refreshAll();
      },
      { once: true }
    );
    form.dispatchEvent(new Event("submit"));
    refreshSidebar(true);
    startAutoRefresh();
  });
}

async function isOnline() {
  if (!navigator.onLine) return false;
  try {
    const response = await fetch("/js/progressx.js", { method: "HEAD", cache: "no-store" });
    return response.ok;
  } catch {return false;}
}

async function updateConnectionStatus() {
  const online = await isOnline();
  const currentlyDown = noResponse > 1 || !online;
  if (currentlyDown && !isDown) {
    isDown = true;
    document.body.classList.add("isDown");
    refreshAll();
  } else if (!currentlyDown && isDown) {
    isDown = false;
    noResponse = 0;
    document.body.classList.remove("isDown");
    refreshSidebar(true);
    startAutoRefresh();
  }
}

function checkConnectionStatus() { updateConnectionStatus(); }

window.addEventListener("online", updateConnectionStatus);
window.addEventListener("offline", updateConnectionStatus);
setInterval(updateConnectionStatus, 15000);

window.addEventListener("message", (event) => {
  if (event.data === "iframeLoaded") { startAutoRefresh(); }
});

function isSidebarVisible() {
  const target = document.getElementById("xhr");
  if (!target) return;

  let debounceTimeout = null;

  const observerCallback = () => {
    if (document.hidden) return;
    const now = Date.now();
    const interval = getRefreshInterval();
    const elapsed = now - lastRefreshTime;
    if (elapsed < interval) {
      if (debounceTimeout) clearTimeout(debounceTimeout);
      debounceTimeout = setTimeout(() => {
        lastRefreshTime = Date.now();
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            refreshSidebar(true);
          });
        });
        startAutoRefresh();
      }, interval - elapsed);
    } else {
      lastRefreshTime = now;
      requestAnimationFrame(() => {
        refreshSidebar(true);
      });
      startAutoRefresh();
    }
  };

  const observer = new MutationObserver(() => {
    observerCallback();
  });

  observer.observe(target, { childList: true, subtree: true, });
}

document.addEventListener("DOMContentLoaded", () => {
  isSidebarVisible();
  initSidebar();
  newHosts();
  start();
});

document.onvisibilitychange = () => {
  lastRefreshTime = 0;
  if (!document.hidden) {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        refreshSidebar(true);
      });
    });
  }
  startAutoRefresh();
};

window.addEventListener("online", () => {
  lastRefreshTime = 0;
  if (!document.hidden) {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        refreshSidebar(true);
      });
    });
  }
  startAutoRefresh();
});

window.addEventListener("offline", () => { checkConnectionStatus(); });

document.addEventListener("DOMContentLoaded", () => {
  initSidebar();
  newHosts();
  start();
});