/* I2P+ RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { newHosts } from "/js/newHosts.js";
import { miniGraph } from "/js/miniGraph.js";

let refreshInterval = refresh * 1000;
let refreshActive = true;
let isPaused = false;
let isDown = false;
let noResponse = 0;
let refreshTimeout = null;
let responseDoc = null;
const parser = new DOMParser();
const sb = document.querySelector("#sidebar");
const uri = location.pathname;
const worker = new SharedWorker("/js/fetchWorker.js");
const elements = {
  badges: sb?.querySelectorAll(".badge:not(#newHosts), #tunnelCount, #newsCount"),
  volatileElements: sb?.querySelectorAll(".volatile:not(.badge)"),
};

async function getRefreshTimerId() {
  return { timerId: refreshTimeout, isActive: refreshActive };
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

worker.port.start();
worker.port.addEventListener("message", event => {
  try {
    const { responseText, isDown: workerIsDown, noResponse: workerNoResponse } = event.data;
    if (responseText) {
      responseDoc = parser.parseFromString(responseText, "text/html");
      isDown = workerIsDown;
      noResponse = workerNoResponse;
      document.body.classList.remove("isDown");
      checkIfDown();
    } else {
      noResponse++;
      checkIfDown();
    }
  } catch {
    noResponse++;
    checkIfDown();
  }
});

async function start() {
  sectionToggler();
  countNewsItems();
  handleFormSubmit();
  startAutoRefresh();
  checkIfDown();
}

function startAutoRefresh() {
  clearTimeout(refreshTimeout);
  refreshTimeout = setTimeout(refreshSidebar, refreshInterval);
}

export async function refreshSidebar(force = false) {
  if (!refreshActive) return;
  try {
    worker.port.postMessage({ url: `/xhr1.jsp?requestURI=${uri}`, force });
    noResponse = 0;
    if (!responseDoc) {
      noResponse++;
      checkIfDown();
      return;
    }
    const updates = [];
    const xhrContainer = document.getElementById("xhr");
    const responseElements = {
      volatileElements: responseDoc.querySelectorAll(".volatile:not(.badge)"),
      badges: responseDoc.querySelectorAll(".badge:not(#newHosts)"),
    };
    const volatile = xhrContainer?.querySelectorAll(".volatile");
    const volatileResponse = responseDoc?.querySelectorAll(".volatile");
    if (volatile?.length !== volatileResponse?.length) {
      refreshAll();
      return;
    }
    elements.volatileElements?.forEach((elem, i) => {
      const respElem = responseElements.volatileElements[i];
      if (elem?.classList.contains("statusDown")) {
        updates.push(() => updateIfStatusDown(elem, respElem));
        refreshAll();
      } else {
        updates.push(() => updateElement(elem, respElem));
      }
    });
    elements.badges?.forEach((elem, i) => {
      const respElem = responseElements.badges[i];
      updates.push(() => updateElement(elem, respElem, "textContent"));
    });
    requestAnimationFrame(() => {
      updates.forEach(fn => fn());
      updates.length = 0;
      countNewsItems();
      newHosts();
      sectionToggler();
    });

    function updateElement(elem, respElem, prop = "innerHTML") {
      if (elem?.[prop] !== respElem?.[prop]) elem[prop] = respElem[prop];
    }
    function updateIfStatusDown(elem, respElem) {
      if (elem?.classList.contains("statusDown") && respElem && elem.outerHTML !== respElem.outerHTML) elem.outerHTML = respElem.outerHTML;
    }
    function refreshAll() {
      if (sb && responseDoc) {
        const sbResponse = responseDoc.getElementById("sb");
        if (sbResponse && sb.innerHTML !== sbResponse.innerHTML) {
          if (xhrContainer) xhrContainer.innerHTML = sbResponse.innerHTML;
        }
      } else {
        noResponse++;
      }
    }
  } catch {
    noResponse++;
  } finally {
    checkIfDown();
    startAutoRefresh();
  }
}

async function doFetch(force = false) {
  try {
    if (force) await refreshSidebar(true);
    else refreshSidebar();
  } catch {
    noResponse++;
  }
}

function checkIfDown() {
  if (noResponse > 5) {
    isDown = true;
    document.body.classList.add("isDown");
  } else {
    isDown = false;
    document.body.classList.remove("isDown");
  }
}

function handleFormSubmit() {
  document.addEventListener("submit", async event => {
    const form = event.target.closest("form");
    const clickTarget = event.submitter;
    if (!form || !clickTarget) return;
    await doFetch(true);
    const formId = form.id;
    const hiddenIframe = document.getElementById("processSidebarForm");
    const handleLoad = () => {
      const formResponse = responseDoc.querySelector(`#${formId}`);
      if (!formResponse) return;
      if (form.id !== "form_sidebar") {
        form.innerHTML = formResponse.innerHTML;
        form.classList.add("activated");
        const shutdownNotice = document.getElementById("sb_shutdownStatus");
        const shutdownNoticeHR = sb?.querySelector("#sb_shutdownStatus+hr");
        const shutdownNoticeResponse = responseDoc.getElementById("sb_shutdownStatus");
        if (shutdownNotice) {
          if (shutdownNoticeResponse?.classList.contains("inactive")) {
            shutdownNotice.hidden = true;
            shutdownNoticeHR.hidden = true;
          } else if (shutdownNoticeResponse && shutdownNotice.innerHTML !== shutdownNoticeResponse.innerHTML) {
            shutdownNotice.hidden = false;
            shutdownNoticeHR.hidden = false;
            shutdownNotice.outerHTML = shutdownNoticeResponse.outerHTML;
          }
        }
        const updateForm = document.getElementById("sb_updateform");
        const updateFormResponse = responseDoc.getElementById("sb_updateform");
        if (updateForm && updateFormResponse?.classList.contains("inactive")) {
          updateForm.hidden = true;
        } else if (updateForm && updateFormResponse && updateForm.innerHTML !== updateFormResponse.innerHTML) {
          updateForm.outerHTML = updateFormResponse.outerHTML;
        }
        if (form.id === "sb_routerControl") {
          const tunnelStatus = document.getElementById("sb_tunnelstatus");
          const tunnelStatusResponse = responseDoc.getElementById("sb_tunnelstatus");
          if (tunnelStatusResponse && tunnelStatus?.innerHTML !== tunnelStatusResponse.innerHTML) tunnelStatus.outerHTML = tunnelStatusResponse.outerHTML;
        }
        const buttons = form.querySelectorAll("button");
        buttons.forEach(btn => {
          btn.style.opacity = ".5";
          btn.style.pointerEvents = "none";
        });
      } else refreshAll();
      hiddenIframe.removeEventListener("load", handleLoad);
    };
    hiddenIframe.removeEventListener("load", handleLoad);
    hiddenIframe.addEventListener("load", handleLoad, { once: true });
    form.dispatchEvent(new Event("submit"));
  });
}

window.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    isPaused = true;
    clearTimeout(refreshTimeout);
  } else if (isPaused) {
    isPaused = false;
    refreshSidebar(true);
    startAutoRefresh();
  }
});

document.addEventListener("DOMContentLoaded", () => {
  initSidebar();
  newHosts();
  start();
});
