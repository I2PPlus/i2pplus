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
let throttleTimer;
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
  document.body.classList.add("isDown");
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

  try {
    const response = await fetch(`/xhr1.jsp?requestURI=${uri}`, { method: "GET", headers: { Accept: "text/html" } });
    if (response.ok) {
      isDown = false;

      const responseText = await response.text(),
        parser = new DOMParser(),
        responseDoc = parser.parseFromString(responseText, "text/html");

      if (isDownTimer !== null) location.reload();
      document.body.classList.remove("isDown");
      if (refreshTimeout) clearTimeout(refreshTimeout);
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
        initSidebar();
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
        if (updating.length !== updatingResponse.length) {
          refreshAll();
        } else {
          updateVolatile();
        }
      })();

      function refreshAll() {
        if (sb && responseDoc) {
          const sbResponse = responseDoc.getElementById("sb");
          if (sbResponse && sb.innerHTML !== sbResponse.innerHTML) {
            requestIdleOrAnimationFrame(() => {
              xhrContainer.innerHTML = sbResponse.innerHTML;
              initSidebar();
            });
          }
        } else {
          tangoDown();
        }
      }

      async function initSidebar() {
        sectionToggler();
        countNewsItems();
      }

      const forms = ["form_reseed", "form_sidebar", "form_updates"].map((id) => document.getElementById(id)).filter((form) => form);
      forms.forEach((form) => (form.onsubmit = () => handleFormSubmit()));
      function handleFormSubmit() {requestIdleOrAnimationFrame(refreshAll);}
    }
  } catch (error) {
    isDown = true;
    await new Promise((resolve) => setTimeout(resolve, 10000));
    await tangoDown();
  }
}

function ready() {
  refreshSidebar(isDocumentVisible)
  .catch((error) => {isDown = true;})
  .finally(() => {
    if (isDown) {setTimeout(tangoDown, 10000);}
  });
}

onVisible(sb, ready);

export { refreshSidebar, requestIdleOrAnimationFrame };