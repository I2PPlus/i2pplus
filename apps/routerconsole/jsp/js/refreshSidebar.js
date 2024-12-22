/* I2P+ RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { onVisible, onHidden } from "/js/onVisible.js";
import { refreshInterval, isDocumentVisible } from "/js/initSidebar.js";

("use strict");

let isDown = false;
let isDownTimer = null;
let refreshTimeout;
let response;
let responseDoc;
let responseText;
let throttleTimer;

const parser = new DOMParser();
const sb = document.querySelector("#sidebar");

const elements = {
  badges: sb.querySelectorAll(".badge, #tunnelCount, #newsCount"),
  volatileElements: sb.querySelectorAll(".volatile:not(.badge)"),
};

const requestIdleOrAnimationFrame = (callback, timeout = refreshInterval) => {
  clearTimeout(throttleTimer);
  throttleTimer = setTimeout(() => {
    const request = typeof requestIdleCallback === "function" ? requestIdleCallback : requestAnimationFrame;
    request(callback);
  }, timeout);
};

sb.addEventListener("loaded", () => { requestIdleOrAnimationFrame(initSidebar); });

function tangoDown() {
  setTimeout(() => { document.body.classList.add("isDown"); }, 3000);
  isDown = true;
  onVisible(sb, () => requestIdleOrAnimationFrame(refreshSidebar));
}

async function refreshSidebar() {
  if (!isDocumentVisible) {
    setTimeout(refreshSidebar, refreshInterval);
    return;
  }

  const uri = location.pathname;
  const xhrContainer = document.getElementById("xhr");

  const doFetch = async () => {
    const uri = location.pathname;
    const xhrContainer = document.getElementById("xhr");
    try {
      response = await fetch(`/xhr1.jsp?requestURI=${uri}`, { method: "GET", headers: { Accept: "text/html" } });
      if (response.ok) {
        isDown = false;
        responseText = await response.text(),
        responseDoc = parser.parseFromString(responseText, "text/html");
      } else {
        isDown = true;
        await new Promise((resolve) => setTimeout(resolve, 10000));
        await tangoDown();
      }
    } catch (error) {}
  };

  await doFetch();

  if (isDownTimer !== null) {location.reload();}

  document.body.classList.remove("isDown");
  if (refreshTimeout) {clearTimeout(refreshTimeout);}
  refreshTimeout = setTimeout(refreshSidebar, refreshInterval);

  const responseElements = {
    volatileElements: responseDoc.querySelectorAll(".volatile:not(.badge)"),
    badges: responseDoc.querySelectorAll(".badge"),
  };

  const updateElementInnerHTML = (elem, respElem) => {
    if (elem && respElem && elem.innerHTML !== respElem.innerHTML) {
      elem.innerHTML = respElem.innerHTML;
    }
  };

  const updateElementTextContent = (elem, respElem) => {
    if (elem && respElem && elem.textContent !== respElem.textContent) {
      elem.textContent = respElem.textContent;
    }
  };

  const updateIfStatusDown = (elem, respElem) => {
    if (elem && elem.classList.contains("statusDown") && respElem && elem.outerHTML !== respElem.outerHTML) {
      elem.outerHTML = respElem.outerHTML;
    }
  };

  const updateVolatile = () => {
    Array.from(elements.volatileElements).forEach((elem, index) => {
      const respElem = responseElements.volatileElements[index];
      if (elem && respElem) {
        if (elem.classList.contains("statusDown")) {
          updateIfStatusDown(elem, respElem);
        } else {
          updateElementInnerHTML(elem, respElem);
        }
      }
    });

    if (elements.badges && responseElements.badges) {
      Array.from(elements.badges).forEach((elem, index) => {
        const respElem = responseElements.badges[index];
        updateElementTextContent(elem, respElem);
      });
    }
  };

  (function checkSections() {
    const updating = xhrContainer.querySelectorAll(".volatile");
    const updatingResponse = responseDoc.querySelectorAll(".volatile");
    if (updating.length !== updatingResponse.length) {refreshAll();}
    else {updateVolatile();}
  })();

  function refreshAll() {
    doFetch();
    if (sb && responseDoc) {
      const sbResponse = responseDoc.getElementById("sb");
      if (sbResponse && sb.innerHTML !== sbResponse.innerHTML) {
        requestIdleOrAnimationFrame(() => {
          xhrContainer.innerHTML = sbResponse.innerHTML;
        });
      }
    } else {tangoDown();}
  }

  await initSidebar();

  async function initSidebar() {
    sectionToggler();
    countNewsItems();
    handleFormSubmit();
  }

  async function handleFormSubmit() {
    document.addEventListener("submit", async function(event) {
      const form = event.target.closest("form");
      const formId = form.getAttribute("id");
      const hiddenIframe = document.getElementById("processSidebarForm");

      hiddenIframe.addEventListener("load", async () => {
        await doFetch();
        const formResponse = responseDoc.querySelector("#" + formId);
        if (formResponse) {
          if (form.id !== "form_sidebar") {
            form.innerHTML = formResponse.innerHTML;
            const shutdownNotice = document.getElementById("sb_shutdownStatus");
            const shutdownNoticeHR = document.querySelector("#sb_shutdownStatus+hr");
            const shutdownNoticeResponse = responseDoc.getElementById("sb_shutdownStatus");
            const updateForm = document.getElementById("sb_updateform");
            const updateFormResponse = responseDoc.getElementById("sb_updateform");
            if (shutdownNoticeResponse.classList.contains("inactive")) {
              shutdownNotice.hidden = true;
              shutdownNoticeHR.hidden = true;
            } else if (shutdownNotice.innerHTML !== shutdownNoticeResponse.innerHTML) {
              shutdownNotice.hidden = false;
              shutdownNoticeHR.hidden = false;
              shutdownNotice.outerHTML = shutdownNoticeResponse.outerHTML;
            }
            if (updateFormResponse.classList.contains("inactive")) {updateForm.hidden = true;}
            else if (updateForm.innerHTML !== updateFormResponse.innerHTML) {
              updateForm.outerHTML = updateFormResponse.outerHTML;
            }
          } else {refreshAll();}
        }
      });
    });
  }

}

async function ready() {
  refreshSidebar(isDocumentVisible)
  .catch((error) => {isDown = true;})
  .finally(() => {
    if (isDown) {setTimeout(tangoDown, 10000);}
  });
}

onVisible(sb, ready);

export { refreshSidebar, requestIdleOrAnimationFrame };