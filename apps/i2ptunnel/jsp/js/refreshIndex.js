/**
 * @module refreshIndex
 * @file refreshIndex.js - Periodic refresh and status updates for I2PTunnel Manager
 * @description Handles auto-refreshing the tunnel index page, updating service status counts,
 * managing router down state overlay, and providing tunnel control functionality
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { initToggleInfo } from "/i2ptunnel/js/toggleTunnelInfo.js";

// Notify parent to reload after action completes
window.addEventListener('message', (e) => {
  if (e.origin !== parentOrigin) return;
  if (e.data === 'actionComplete') {
    window.parent.location.reload();
  }
});

// If this page was loaded in iframe after an action, notify parent to reload
if (window.self !== window.top) {
  const params = new URLSearchParams(window.location.search);
  if (params.has('action') && params.get('action') !== 'list') {
    window.parent.postMessage('actionComplete', parentOrigin);
  }
}

/** @type {HTMLElement|null} */
const container = document.querySelector('#page');
/** @type {HTMLElement|null} */
const control = document.getElementById("globalTunnelControl");
/** @type {HTMLElement|null} */
const countClient = document.getElementById("countClient");
/** @type {HTMLElement|null} */
const countServer = document.getElementById("countServer");
/** @type {HTMLIFrameElement|null} */
let iframe = null;
try {
  if (window.self !== window.top) {
    iframe = window.parent.document.querySelector(".main#tunnelmgr");
  }
} catch (e) {
  console.warn('[refreshIndex] Cannot access parent document:', e);
}
/** @type {HTMLElement|null} */
const isDownElement = document.getElementById("down");
/** @type {HTMLElement|null} */
const messages = document.getElementById("tunnelMessages");
/** @type {HTMLElement|null} */
const notReady = document.getElementById("notReady");
/** @type {HTMLStyleElement|null} */
const tempStylesheet = document.head.querySelector("#isDownOverlay");
/** @type {HTMLElement|null} */
const toggle = document.getElementById("toggleInfo");
/** @type {HTMLElement|null} */
const tunnelIndex = document.getElementById("page");
/** @type {string} */
const url = window.location.pathname;
/** @type {string} */
const safeTheme = typeof theme !== 'undefined' ? theme : 'dark';
/** @type {string} */
const parentOrigin = window.parent?.location?.origin || location.origin;
/** @type {WeakSet} */
const boundToggles = new WeakSet();

/** @type {boolean} */
let isDownClassAdded = false;
/** @type {number|undefined} */
let isDownTimeoutId;
/** @type {boolean} */
let tunnelControlListenerAttached = false;

/**
 * Updates element content if it differs from the response element.
 * @function updateElementContent
 * @param {HTMLElement} element - The DOM element to update
 * @param {HTMLElement} responseElement - The element containing new content
 * @returns {void}
 */
function updateElementContent(element, responseElement) {
  if (element && responseElement && element.innerHTML !== responseElement.innerHTML) {
    element.innerHTML = responseElement.innerHTML;
  }
}

/**
 * Fetches the current page and updates tunnel status information.
 * On success, clears down state, parses response HTML, and updates volatile content.
 * On failure, schedules down state handler after 5 second delay.
 * @async
 * @function refreshTunnelStatus
 * @returns {Promise<void>}
 */
async function refreshTunnelStatus() {
  try {
    const response = await fetch(url, { method: "GET", headers: { "Cache-Control": "no-cache" }, credentials: 'same-origin' });
    if (response.ok) {
      clearTimeout(isDownTimeoutId);
      if (isDownElement) { isDownElement.remove(); }
      if (tempStylesheet) { tempStylesheet.remove(); }

      const doc = new DOMParser().parseFromString(await response.text(), "text/html");

      requestAnimationFrame(() => { resizeIframe(); });

      if (isDownClassAdded && doc.getElementById("globalTunnelControl")) { reloadPage(); }
      document.body.classList.remove("isDown");
      if (container) { container.style = ""; }
      if (iframe) { iframe.style.padding = ""; }

      if (notReady || doc.getElementById("notReady")) {
        const notReadyResponse = doc.getElementById("notReady");
        if (notReadyResponse) { refreshAll(doc); }
        else { reloadPage(); }
      } else {
        updateNonceLinks(doc);
        updateVolatile(doc);
      }

      countServices();
      resizeIframe();
      isDownClassAdded = false;
    } else {
      isDownTimeoutId = setTimeout(handleDownState, 5000);
    }
  } catch (error) {
    isDownTimeoutId = setTimeout(handleDownState, 5000);
  }
}

/**
 * Displays the 'router is down' overlay and schedules a re-check.
 * Creates a full-screen overlay with "Router is down" message,
 * adds temporary stylesheet for visual effect, and resizes iframe.
 * @function handleDownState
 * @returns {void}
 */
function handleDownState() {
  const viewportHeight = window.innerHeight;
  if (!document.body.classList.contains("isDown") && !document.getElementById("down")) {
    document.body.classList.add("isDown");
    if (container) {
      container.style.height = "100%";
      container.style.minHeight = `${viewportHeight}px`;
      container.style.overflow = 'hidden';
    }
    const downElement = document.createElement("div");
    downElement.id = "down";
    downElement.className = "notReady";
    downElement.innerHTML = "<b><span>Router is down</span></b>";
    downElement.style.zIndex = "9999";
    document.body.appendChild(downElement);
    if (!tempStylesheet) {
      const styleSheet = document.createElement("style");
      styleSheet.id = "isDownOverlay";
      const bg = (safeTheme === "light" || safeTheme === "classic" ? "#f2f2ff" : "#000");
      const styles =
        "body.isDown{display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}" +
        "body.isDown::after{position:fixed;top:0;right:0;bottom:0;left:0;z-index:998;background:" + bg + ";content:''}" +
        "body.isDown #page{display:none}";
      styleSheet.appendChild(document.createTextNode(styles));
      document.head.appendChild(styleSheet);
    }
    if (iframe) {
      iframe.style.padding = "0";
      requestAnimationFrame(() => {resizeIframe();});
    }
  }
  refreshTunnelStatus();
  isDownClassAdded = true;
}

/**
 * Counts tunnel services by status and updates the UI count displays.
 * Iterates through status categories (running, standby, starting, stopped)
 * and updates both client and server count elements.
 * @function countServices
 * @returns {void}
 */
function countServices() {
  const statuses = [
    { clientSelector: ".cli.statusRunning", serverSelector: ".svr.statusRunning", status: "running" },
    { clientSelector: ".cli.statusStandby", serverSelector: ".svr.statusStandby", status: "standby" },
    { clientSelector: ".cli.statusStarting", serverSelector: ".svr.statusStarting", status: "starting" },
    { clientSelector: ".cli.statusNotRunning", serverSelector: ".svr.statusNotRunning", status: "stopped" }
  ];

  /**
   * Sets the status class on a table row element, removing other status classes.
   * @function setStatusClass
   * @param {HTMLElement} parentTr - The table row element
   * @param {string} status - The status class to apply (running, standby, starting, stopped)
   * @returns {void}
   */
  const setStatusClass = (parentTr, status) => {
    const statusClasses = statuses.map(s => s.status);
    statusClasses.forEach(s => parentTr.classList.remove(s));
    parentTr.classList.add(status);
  };

  /**
   * Updates the count display for a given selector and status.
   * @function updateCount
   * @param {HTMLElement} element - The element displaying the count
   * @param {string} selector - CSS selector for tunnel elements
   * @param {string} status - The status category
   * @returns {void}
   */
  const updateCount = (element, selector, status) => {
    if (!element) return;
    const elements = document.querySelectorAll(selector);
    element.textContent = elements.length > 0 ? ` x ${elements.length}` : "";

    elements.forEach(el => {
      const parentTr = el.closest("tr");
      if (parentTr) { setStatusClass(parentTr, status); }
    });
  };

  if (control) {
    statuses.forEach(({ clientSelector, serverSelector, status }) => {
      if (countClient) {
        updateCount(countClient.querySelector(`.${status}`), clientSelector, status);
      }
      if (countServer) {
        updateCount(countServer.querySelector(`.${status}`), serverSelector, status);
      }
    });
  }
}

/**
 * Updates volatile tunnel property elements from the response document.
 * If element count differs, refreshes full panels; otherwise updates changed elements.
 * @function updateVolatile
 * @param {Document} responseDoc - The parsed HTML document from fetch response
 * @returns {void}
 */
function updateVolatile(responseDoc) {
  const updating = document.querySelectorAll(".tunnelProperties .volatile");
  const updatingResponse = responseDoc.querySelectorAll(".tunnelProperties .volatile");
  updateLog(responseDoc);
  if (updating.length !== updatingResponse.length) {
    refreshPanels(responseDoc);
  } else {
    updating.forEach((el, i) => {
      const responseEl = updatingResponse[i];
      if (el.textContent.trim() !== responseEl.textContent.trim()) {
        updateElementContent(el, responseEl);
      }
    });
  }
}

/**
 * Updates the tunnel messages log from the response document.
 * If messages element exists in response but not locally, triggers full page refresh.
 * @function updateLog
 * @param {Document} responseDoc - The parsed HTML document from fetch response
 * @returns {void}
 */
function updateLog(responseDoc) {
  const messagesResponse = responseDoc?.getElementById("tunnelMessages");
  updateElementContent(messages, messagesResponse);
  if (messagesResponse && !messages) {refreshAll(responseDoc);}
}

/**
 * Refreshes server and client tunnel panels from the response document.
 * @function refreshPanels
 * @param {Document} responseDoc - The parsed HTML document from fetch response
 * @returns {void}
 */
function refreshPanels(responseDoc) {
  updateLog(responseDoc);
  updateNonceLinks(responseDoc);
  ["serverTunnels", "clientTunnels"].forEach(id => {
    const element = document.getElementById(id);
    const responseElement = responseDoc.getElementById(id);
    updateElementContent(element, responseElement);
  });
}

/**
 * Performs a full page content refresh from the response document.
 * Updates the page element, reinitializes tunnel control, binds toggle,
 * recounts services, and resizes iframe.
 * @function refreshAll
 * @param {Document} responseDoc - The parsed HTML document from fetch response
 * @returns {void}
 */
function refreshAll(responseDoc) {
  const tunnelIndexResponse = responseDoc?.getElementById("page");
  updateElementContent(tunnelIndex, tunnelIndexResponse);
  updateNonceLinks(responseDoc);
  initTunnelControl();
  bindToggle();
  countServices();
  resizeIframe();
}

/**
 * Forces a hard reload of the current page, bypassing cache.
 * @function reloadPage
 * @returns {void}
 */
function reloadPage() {location.reload(true);}

/**
 * Updates nonce values in all action forms from the response document.
 * Parses new nonce from first form input and updates all form nonce inputs in DOM.
 * @function updateNonceLinks
 * @param {Document} responseDoc - The parsed HTML document from fetch response
 * @returns {void}
 */
function updateNonceLinks(responseDoc) {
  const firstForm = responseDoc.querySelector('form[id] input[name="nonce"]');
  if (!firstForm) { console.warn('[refreshIndex] No nonce form found in response'); return; }
  const newNonce = firstForm.value;
  if (!newNonce) { console.warn('[refreshIndex] Empty nonce value in response'); return; }
  document.querySelectorAll('form[id] input[name="nonce"]').forEach(input => {
    if (input.value !== newNonce) {
      input.value = newNonce;
    }
  });
}

/**
 * Binds the toggle info click handler if not already attached.
 * Prevents duplicate event listeners by checking for 'listener' class.
 * @function bindToggle
 * @returns {void}
 */
function bindToggle() {
  if (toggle && !boundToggles.has(toggle)) {
    toggle.addEventListener("click", initToggleInfo);
    boundToggles.add(toggle);
  }
}

/**
 * Main refresh cycle function called on interval.
 * Refreshes tunnel status and ensures control bindings are active.
 * If control element is missing, retries after 500ms delay.
 * @function refreshIndex
 * @returns {void}
 */
function refreshIndex() {
  refreshTunnelStatus();
  if (!control) { setTimeout(refreshIndex, 500); return; }
  bindToggle();
  if (tunnelIndex && !tunnelIndex.classList.contains("listener")) {initTunnelControl();}
}

/**
 * Initializes tunnel control click handlers for start/stop/restart actions.
 * Attaches delegated click listener on the tunnel index for control buttons.
 * @function initTunnelControl
 * @returns {void}
 */
function initTunnelControl() {
  if (!tunnelIndex || tunnelControlListenerAttached) return;
  tunnelControlListenerAttached = true;

  tunnelIndex.addEventListener("click", async event => {
    const target = event.target.closest("button[name='action']");
    if (target && target.classList.contains("control") && !target.classList.contains("create") && !target.classList.contains("preview")) {
      event.preventDefault();
      const formId = target.getAttribute("form");
      const form = document.getElementById(formId);
      if (!form) {
        console.warn(`[refreshIndex] Form not found: ${formId}`);
        if (messages) messages.innerHTML = `<li class="error">Error: Form "${formId}" not found</li>`;
        return;
      }
      await tunnelControl(form, target.value);
    }
  });
}

/**
 * Executes a tunnel control action via POST and updates the UI.
 * @async
 * @function tunnelControl
 * @param {HTMLFormElement} form - The form element containing action parameters
 * @param {string} action - The action value from the clicked button
 * @returns {Promise<void>}
 */
async function tunnelControl(form, action) {
  try {
    const url = new URL(form.getAttribute("action"), window.location.href).href;
    const formParams = new URLSearchParams();
    formParams.set('nonce', form.querySelector('input[name="nonce"]').value);
    formParams.set('tunnel', form.querySelector('input[name="tunnel"]').value);
    formParams.set('action', action);

    const response = await fetch(url, {
      method: "POST",
      headers: { "Cache-Control": "no-cache" },
      credentials: 'same-origin',
      body: formParams
    });

    if (!response.ok) {
      const errorText = await response.text().catch(() => '');
      console.error(`[refreshIndex] Action failed: ${response.status} ${response.statusText}`, errorText);
      if (messages) {
        messages.innerHTML = `<li class="error">Server error ${response.status}: ${response.statusText}</li>`;
      }
      return;
    }

    const doc = new DOMParser().parseFromString(await response.text(), "text/html");
    const errorEl = doc.querySelector(".error");
    const messagesEl = doc.getElementById("tunnelMessages");

    if (errorEl && messages) {
      messages.innerHTML = errorEl.outerHTML;
    }
    if (messagesEl && messages) {
      messages.innerHTML = messagesEl.innerHTML;
    }

    if (errorEl && errorEl.textContent.includes("Invalid form submission")) {
      await refreshTunnelStatus();
      const freshForm = document.getElementById(form.id);
      if (!freshForm) {
        console.warn(`[refreshIndex] Fresh form not found after refresh: ${form.id}`);
        return;
      }
      const retryParams = new URLSearchParams(new FormData(freshForm));
      const retryResponse = await fetch(url, {
        method: "POST",
        headers: { "Cache-Control": "no-cache" },
        credentials: 'same-origin',
        body: retryParams
      });

      if (!retryResponse.ok) {
        if (messages) {
          messages.innerHTML = `<li class="error">Retry failed: ${retryResponse.status}</li>`;
        }
        return;
      }

      const retryDoc = new DOMParser().parseFromString(await retryResponse.text(), "text/html");
      updateNonceLinks(retryDoc);
      countServices();
      updateVolatile(retryDoc);
      return;
    }

    updateNonceLinks(doc);
    countServices();
    updateVolatile(doc);

  } catch (e) {
    console.error(e);
    if (messages) {
      messages.innerHTML = `<li class="error">Action failed: ${e.message || e}</li>`;
    }
  }
}

/**
 * Resizes the parent iframe to fit content by posting a resize message.
 * Only executes if the page is running inside an iframe.
 * @function resizeIframe
 * @returns {void}
 */
function resizeIframe() {
  const isIframed = document.documentElement.classList.contains("iframed") || window.self !== window.top;
  if (isIframed) {
    setTimeout(() => {
      parent.postMessage({ action: 'resize', iframeId: 'i2ptunnelframe' }, parentOrigin);
    }, 100);
  }
}

/**
 * Preloads an image into browser cache for faster subsequent display.
 * @function preloadImage
 * @param {string} url - The URL of the image to preload
 * @returns {void}
 * @example
 * preloadImage("/themes/console/dark/images/tunnelmanager.webp");
 */
function preloadImage(url) {
  const img = new Image();
  img.src = url;
}

// Preload image immediately for isDown display (before router potentially goes down)
if (safeTheme === "dark") {
  preloadImage("/themes/console/dark/images/tunnelmanager.webp");
}

/**
 * DOMContentLoaded handler that initializes service counting and bindings.
 * @listens DOMContentLoaded
 */
document.addEventListener("DOMContentLoaded", () => {
  countServices();
  if (toggle) { bindToggle(); }
  if (control) { initTunnelControl(); }

  setInterval(refreshIndex, 5000);
  refreshIndex();
});

/**
 * Window load handler that resizes iframe if page is embedded.
 * @listens load
 */
window.addEventListener("load", function () {
  if (window.self !== window.top) { resizeIframe(); }
});
