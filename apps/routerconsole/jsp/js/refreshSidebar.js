/* I2P+ RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { newHosts } from "/js/newHosts.js";
import { miniGraph } from "/js/miniGraph.js";

let refreshActive = true, isPaused = false, isDown = false, noResponse = 0, refreshTimeout = null, responseDoc = null;
let lastRefreshTime = 0;
let isRefreshing = false;
let xhrContainer = document.getElementById("xhr");
const parser = new DOMParser(), sb = document.querySelector("#sidebar"), uri = location.pathname;
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
  const setup = () => { sectionToggler(); stickySidebar(); miniGraph(); };
  document.addEventListener("DOMContentLoaded", setup);
  window.addEventListener("resize", stickySidebar, { passive: true });
}

function getRefreshInterval() {
  if (document.hidden) {return 15*60*1000;}
  return refresh * 1000;
}

worker.port.start();
worker.port.addEventListener("message", ({ data }) => {
  try {
    const { responseText, isDown: workerIsDown, noResponse: workerNoResponse } = data;
    if (responseText) {
      responseDoc = parser.parseFromString(responseText, "text/html");
      isDown = workerIsDown;
      noResponse = workerNoResponse;
      document.body.classList.remove("isDown");
      requestAnimationFrame(startAutoRefresh);
    } else noResponse++;
  } catch {
    noResponse++;
  }
  checkIfDown();
});

async function start() {
  checkIfDown(); sectionToggler(); countNewsItems(); handleFormSubmit(); startAutoRefresh();
}

function startAutoRefresh() {
  isRefreshing = false;
  clearTimeout(refreshTimeout);
  const now = Date.now();
  const interval = getRefreshInterval();
  if (lastRefreshTime === 0) {lastRefreshTime = now;}
  else {
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
  if (!refreshActive || isRefreshing) return;
  isRefreshing = true;
  try {
    worker.port.postMessage({ url: `/xhr1.jsp?requestURI=${uri}`, force });
    noResponse = 0;

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
        updates.push(() => elem.innerHTML = respElem.innerHTML);
      }
    });

    elements.badges.forEach((elem, i) => {
      const respElem = responseElements.badges[i];
      if ((respElem && elem.textContent !== respElem.textContent) ||
        elem.id === "lsCount" ||
        elem.id === "sb_updateform" ||
        elem.id === "sb_shutdownStatus") {
        updates.push(() => elem.textContent = respElem?.textContent);
      }
    });

    requestAnimationFrame(() => {
      updates.forEach(fn => fn());
      countNewsItems();
      newHosts();
      sectionToggler();
      updates.length = 0;
    });

  } catch {
    noResponse++;
    startAutoRefresh();
  } finally {
    checkIfDown();
    isRefreshing = false;
  }
}

function refreshAll() {
  if (!(sb && responseDoc)) return noResponse++;
  updateCachedElements();
  const sbResponse = responseDoc.getElementById("sb");
  if (sbResponse && xhrContainer && sb.innerHTML !== sbResponse.innerHTML) {
    xhrContainer.innerHTML = sbResponse.innerHTML;
  }
  noResponse = 0;
}

function checkIfDown() {
  isDown = noResponse > 2;
  document.body.classList.toggle("isDown", isDown);
  if (isDown) {refreshAll();}
}

function handleFormSubmit() {
  document.addEventListener("submit", (event) => {
    const form = event.target.closest("form"), clickTarget = event.submitter;
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

        form.querySelectorAll("button").forEach(btn => {
          btn.style.opacity = ".5";
          btn.style.pointerEvents = "none";
        });

      } else refreshAll();

    }, { once: true });

    form.dispatchEvent(new Event("submit"));
    refreshSidebar(true);
    startAutoRefresh();
  });
}

document.onvisibilitychange = () => {
  isRefreshing = false;
  lastRefreshTime = 0;
  if (!document.hidden) {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => { refreshSidebar(true); });
    });
  }
  startAutoRefresh();
};

document.addEventListener("DOMContentLoaded", () => {
  initSidebar();
  newHosts();
  start();
});