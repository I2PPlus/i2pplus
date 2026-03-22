/**
 * @module refreshIndex
 * @file refreshIndex.js - Periodic refresh and status updates for I2PTunnel Manager
 * @description Handles auto-refreshing the tunnel index page, updating service status counts,
 * managing router down state overlay, and providing tunnel control functionality
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { initToggleInfo } from "/i2ptunnel/js/toggleTunnelInfo.js";

/** @type {HTMLElement|null} */
const container = document.querySelector('#page');
/** @type {HTMLElement|null} */
const control = document.getElementById("globalTunnelControl");
/** @type {HTMLElement|null} */
const countClient = document.getElementById("countClient");
/** @type {HTMLElement|null} */
const countServer = document.getElementById("countServer");
/** @type {HTMLIFrameElement|null} */
const iframe = window.parent.document.querySelector(".main#tunnelmgr");
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

/** @type {boolean} */
let isDownClassAdded = false;
/** @type {number|undefined} */
let isDownTimeoutId;

/**
 * Updates element content if it differs from the response element.
 * @function updateElementContent
 * @param {HTMLElement} element - The DOM element to update
 * @param {HTMLElement} responseElement - The element containing new content
 * @returns {void}
 */
function updateElementContent(element, responseElement) {
  if (responseElement && element.innerHTML !== responseElement.innerHTML) {
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
    const response = await fetch(url, { method: "GET", headers: { "Cache-Control": "no-cache" } });
    if (response.ok) {
      clearTimeout(isDownTimeoutId);
      if (isDownElement) { isDownElement.remove(); }
      if (tempStylesheet) { tempStylesheet.remove(); }

      const doc = new DOMParser().parseFromString(await response.text(), "text/html");

      requestAnimationFrame(() => { resizeIframe(); });

      if (isDownClassAdded && doc.getElementById("globalTunnelControl")) { reloadPage(); }
      document.body.classList.remove("isDown");
      container.style = "";
      iframe.style.padding = "";

      if (notReady) {
        const notReadyResponse = doc.getElementById("notReady");
        if (notReadyResponse) { refreshAll(doc); }
        else { reloadPage(); }
      } else { updateVolatile(doc); }

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
    container.style.height = `${viewportHeight}px`;
    container.style.overflow = 'hidden';
    const downElement = document.createElement("div");
    downElement.id = "down";
    downElement.className = "notReady";
    downElement.innerHTML = "<b><span>Router is down</span></b>";
    downElement.style.position = "absolute";
    downElement.style.top = "50%";
    downElement.style.left = "50%";
    downElement.style.transform = "translate(-50%, -50%)";
    downElement.style.zIndex = "9999";
    document.body.appendChild(downElement);
    if (!tempStylesheet) {
      const styleSheet = document.createElement("style");
      styleSheet.id = "isDownOverlay";
      const bg = (theme === "light" || theme === "classic" ? "#f2f2ff" : "#000");
      const styles =
        ".isDown::after{width:calc(100% + 16px);height:100vh;display:block;position:fixed;top:0;right:0;bottom:0;left:0;z-index:999;background:" + bg +
        ";overflow:hidden;contain:paint;content:''}" +
        ".isDown #page{display:inline-block;max-height:600px}";
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
    const elements = document.querySelectorAll(selector);
    element.textContent = elements.length > 0 ? ` x ${elements.length}` : "";

    elements.forEach(el => {
      const parentTr = el.closest("tr");
      if (parentTr) { setStatusClass(parentTr, status); }
    });
  };

  if (control) {
    statuses.forEach(({ clientSelector, serverSelector, status }) => {
      updateCount(countClient.querySelector(`.${status}`), clientSelector, status);
      updateCount(countServer.querySelector(`.${status}`), serverSelector, status);
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
  if (updating.length !== updatingResponse.length) {refreshPanels(responseDoc);}
  else {
    updating.forEach((el, i) => {
      const responseEl = updatingResponse[i];
      if (el.textContent.trim() !== responseEl.textContent.trim()) {updateElementContent(el, responseEl);}
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
 * Binds the toggle info click handler if not already attached.
 * Prevents duplicate event listeners by checking for 'listener' class.
 * @function bindToggle
 * @returns {void}
 */
function bindToggle() {
  if (toggle && !control.classList.contains("listener")) {
    toggle.addEventListener("click", initToggleInfo);
    control.classList.add("listener");
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
  if (!tunnelIndex.classList.contains("listener")) {initTunnelControl();}
}

/**
 * Initializes tunnel control click handlers for start/stop/restart actions.
 * Attaches delegated click listener on the tunnel index for control buttons.
 * @function initTunnelControl
 * @returns {void}
 */
function initTunnelControl() {
  if (!tunnelIndex.classList.contains("listener")) {
    tunnelIndex.addEventListener("click", async event => {
      const target = event.target;
      if (target.classList.contains("control") && !target.classList.contains("create")) {
        event.preventDefault();
        await tunnelControl(target.href, target);
        tunnelIndex.classList.add("listener");
      }
    });
  }

  /**
   * Executes a tunnel control action by fetching the URL and updating the UI.
   * @async
   * @function tunnelControl
   * @param {string} url - The control action URL to fetch
   * @param {HTMLElement} target - The clicked control element
   * @returns {Promise<void>}
   */
  async function tunnelControl(url, target) {
    try {
      const response = await fetch(url, { method: "GET", headers: { "Cache-Control": "no-cache" } });
      if (response.ok) {
        const doc = new DOMParser().parseFromString(await response.text(), "text/html");
        countServices();
        updateVolatile(doc);
      }
    } catch {}
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
      parent.postMessage({ action: 'resize', iframeId: 'i2ptunnelframe' }, location.origin);
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

if (toggle) { bindToggle(); }
if (control) { initTunnelControl(); }
/** @type {number} */
setInterval(refreshIndex, 5000);

/**
 * DOMContentLoaded handler that initializes service counting and preloads dark theme images.
 * @listens DOMContentLoaded
 */
document.addEventListener("DOMContentLoaded", () => {
  countServices();
  if (theme === "dark") {
    preloadImage("/themes/console/dark/images/tunnelmanager.webp");
  }
});

/**
 * Window load handler that resizes iframe if page is embedded.
 * @listens load
 */
window.addEventListener("load", function () {
  if (window.self !== window.top) { resizeIframe(); }
});
