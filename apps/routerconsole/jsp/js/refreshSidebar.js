/* I2P+ RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { onVisible, onHidden } from "/js/onVisible.js";
import { refreshInterval, getRefreshTimerId } from "/js/initSidebar.js";

("use strict");

let isDown = false;
let noResponse = 0;
let refreshTimeout;
let refreshTimerActive = true;
let response;
let responseDoc;
let responseText;

const parser = new DOMParser();
const sb = document.querySelector("#sidebar");
const uri = location.pathname;
const worker = new Worker("/js/fetchWorker.js");

const elements = {
  badges: sb.querySelectorAll(".badge, #tunnelCount, #newsCount"),
  volatileElements: sb.querySelectorAll(".volatile:not(.badge)"),
};

async function initSidebar() {
  sectionToggler();
  handleFormSubmit();
  countNewsItems();
  checkTimer();
  checkIfDown();
}

async function checkIfDown() {
  if (noResponse > 4) {
    isDown = true;
    document.body.classList.add("isDown");
  }
}

async function checkTimer() {
  refreshTimerActive = await getRefreshTimerId();
}

async function doFetch(force = false) {
  checkTimer();
  if (!refreshTimerActive.isActive) {return;}
  try {
    worker.postMessage({ url: `/xhr1.jsp?requestURI=${uri}` });
    if (refreshTimeout) {clearTimeout(refreshTimeout);}
    refreshTimeout = setTimeout(doFetch, refreshInterval);
  } catch (error) {noResponse++;}
  if (force) {refreshSidebar();}
}

worker.addEventListener("message", async function(event) {
  try {
    let { responseText, isDown, noResponse: workerNoResponse } = event.data;
    responseDoc = parser.parseFromString(responseText, "text/html");
    isDown = false;
    noResponse = workerNoResponse;
    document.body.classList.remove("isDown");
    checkIfDown();
  } catch (error) {
    noResponse++;
    checkIfDown();
  }
});

async function refreshSidebar() {
  const xhrContainer = document.getElementById("xhr");
  try {
    await doFetch();
    if (refreshTimeout) {clearTimeout(refreshTimeout);}
    refreshTimeout = setTimeout(refreshSidebar, refreshInterval);
    if (responseDoc) {
      isDown = false;
      const responseElements = {
        volatileElements: responseDoc.querySelectorAll(".volatile:not(.badge)"),
        badges: responseDoc.querySelectorAll(".badge"),
      };

      const updateElementInnerHTML = (elem, respElem) => {
        if (elem && respElem && elem.innerHTML != respElem.innerHTML) {
          elem.innerHTML = respElem.innerHTML;
        }
      };

      const updateElementTextContent = (elem, respElem) => {
        if (elem && respElem && elem.textContent != respElem.textContent) {
          elem.textContent = respElem.textContent;
        }
      };

      const updateIfStatusDown = (elem, respElem) => {
        if (elem && elem.classList.contains("statusDown") && respElem && elem.outerHTML != respElem.outerHTML) {
          elem.outerHTML = respElem.outerHTML;
        }
      };

      (function checkSections() {
        const updating = xhrContainer.querySelectorAll(".volatile");
        const updatingResponse = responseDoc.querySelectorAll(".volatile");
        if (updating.length !== updatingResponse.length) {refreshAll();}
        else {updateVolatile();}
        countNewsItems();
      })();

      function updateVolatile() {
        Array.from(elements.volatileElements).forEach((elem, index) => {
          const respElem = responseElements.volatileElements[index];
          if (elem && respElem) {
            if (elem.classList.contains("statusDown")) {updateIfStatusDown(elem, respElem);}
            else {updateElementInnerHTML(elem, respElem);}
          }
        });

        if (elements.badges && responseElements.badges) {
          Array.from(elements.badges).forEach((elem, index) => {
            const respElem = responseElements.badges[index];
            updateElementTextContent(elem, respElem);
          });
        }
      };

      function refreshAll() {
        if (sb && responseDoc) {
          const sbResponse = responseDoc.getElementById("sb");
          if (sbResponse && sb.innerHTML !== sbResponse.innerHTML) {
            xhrContainer.innerHTML = sbResponse.innerHTML;
          }
          sectionToggler();
        } else {
          noResponse++;
          checkIfDown();
        }
      }
    } else {
      noResponse++;
      checkIfDown();
    }
  } catch (error) {
    noResponse++;
    checkIfDown();
  }
}

async function handleFormSubmit() {
  document.addEventListener("submit", async (event) => {
    const form = event.target.closest("form");
    if (form) {
      const formId = form.getAttribute("id");
      const hiddenIframe = document.getElementById("processSidebarForm");
      hiddenIframe.removeEventListener("load", handleLoad);
      hiddenIframe.addEventListener("load", handleLoad);
      form.dispatchEvent(new Event('submit'));
      function handleLoad(event) {
        setTimeout(async () => {
          await doFetch(true);
          const formResponse = responseDoc.querySelector(`#${formId}`);
          if (formResponse) {
            if (form.id !== "form_sidebar") {
              form.innerHTML = formResponse.innerHTML;
              const shutdownNotice = document.getElementById("sb_shutdownStatus");
              const shutdownNoticeHR = sb.querySelector("#sb_shutdownStatus+hr");
              const shutdownNoticeResponse = responseDoc.getElementById("sb_shutdownStatus");
              const updateForm = document.getElementById("sb_updateform");
              const updateFormResponse = responseDoc.getElementById("sb_updateform");

              if (shutdownNotice) {
                if (shutdownNoticeResponse && shutdownNoticeResponse.classList.contains("inactive")) {
                  shutdownNotice.hidden = true;
                  shutdownNoticeHR.hidden = true;
                } else if (shutdownNoticeResponse && shutdownNotice.innerHTML !== shutdownNoticeResponse.innerHTML) {
                  shutdownNotice.hidden = false;
                  shutdownNoticeHR.hidden = false;
                  shutdownNotice.outerHTML = shutdownNoticeResponse.outerHTML;
                }
              }
              if (updateForm && updateFormResponse && updateFormResponse.classList.contains("inactive")) {
                updateForm.hidden = true;
              } else if (updateForm && updateFormResponse && updateForm.innerHTML !== updateFormResponse.innerHTML) {
                updateForm.outerHTML = updateFormResponse.outerHTML;
              }
              if (form.id === "sb_routerControl") {
                const tunnelStatus = document.getElementById("sb_tunnelstatus");
                const tunnelStatusResponse = responseDoc.getElementById("sb_tunnelstatus");
                if (tunnelStatusResponse && tunnelStatus.innerHTML !== tunnelStatusResponse.innerHTML) {
                  tunnelStatus.outerHTML = tunnelStatusResponse.outerHTML;
                }
              }
            } else {refreshAll();}
          }
        }, 100);
      }
    }
  });
}

const ready = async() => {
  try {await refreshSidebar();}
  catch (error) {}
}

onVisible(sb, ready);
document.addEventListener("DOMContentLoaded", initSidebar);

export { refreshSidebar };