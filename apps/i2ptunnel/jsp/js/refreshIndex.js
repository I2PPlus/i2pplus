/* I2P+ refreshIndex.js for I2PTunnel Manager by dr|z3d */
/* Periodically refresh the index and update status counts */
/* License: AGPL3 or later */

import { initToggleInfo } from "/i2ptunnel/js/toggleTunnelInfo.js";

const container = document.querySelector('#page');
const control = document.getElementById("globalTunnelControl");
const countClient = document.getElementById("countClient");
const countServer = document.getElementById("countServer");
const iframe = window.parent.document.querySelector(".main#tunnelmgr");
const isDownElement = document.getElementById("down");
const messages = document.getElementById("tunnelMessages");
const notReady = document.getElementById("notReady");
const tempStylesheet = document.head.querySelector("#isDownOverlay");
const toggle = document.getElementById("toggleInfo");
const tunnelIndex = document.getElementById("page");
const url = window.location.pathname;

let isDownClassAdded = false;
let isDownTimeoutId;

function updateElementContent(element, responseElement) {
  if (responseElement && element.innerHTML !== responseElement.innerHTML) {
    element.innerHTML = responseElement.innerHTML;
  }
}

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

function countServices() {
  const statuses = [
    { clientSelector: ".cli.statusRunning", serverSelector: ".svr.statusRunning", status: "running" },
    { clientSelector: ".cli.statusStandby", serverSelector: ".svr.statusStandby", status: "standby" },
    { clientSelector: ".cli.statusStarting", serverSelector: ".svr.statusStarting", status: "starting" },
    { clientSelector: ".cli.statusNotRunning", serverSelector: ".svr.statusNotRunning", status: "stopped" }
  ];

  const setStatusClass = (parentTr, status) => {
    const statusClasses = statuses.map(s => s.status);
    statusClasses.forEach(s => parentTr.classList.remove(s));
    parentTr.classList.add(status);
  };

  const updateCount = (element, selector, status) => {
    const elements = document.querySelectorAll(selector);
    element.textContent = elements.length > 0 ? ` x ${elements.length}` : "";

    elements.forEach(el => {
      const parentTr = el.closest("tr");
      if (parentTr) setStatusClass(parentTr, status);
    });
  };

  if (control) {
    statuses.forEach(({ clientSelector, serverSelector, status }) => {
      updateCount(countClient.querySelector(`.${status}`), clientSelector, status);
      updateCount(countServer.querySelector(`.${status}`), serverSelector, status);
    });
  }
}

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

function updateLog(responseDoc) {
  const messagesResponse = responseDoc?.getElementById("tunnelMessages");
  updateElementContent(messages, messagesResponse);
  if (messagesResponse && !messages) {refreshAll(responseDoc);}
}

function refreshPanels(responseDoc) {
  updateLog(responseDoc);
  ["serverTunnels", "clientTunnels"].forEach(id => {
    const element = document.getElementById(id);
    const responseElement = responseDoc.getElementById(id);
    updateElementContent(element, responseElement);
  });
}

function refreshAll(responseDoc) {
  const tunnelIndexResponse = responseDoc?.getElementById("page");
  updateElementContent(tunnelIndex, tunnelIndexResponse);
  initTunnelControl();
  bindToggle();
  countServices();
  resizeIframe();
}

function reloadPage() {location.reload(true);}

function bindToggle() {
  if (toggle && !control.classList.contains("listener")) {
    toggle.addEventListener("click", initToggleInfo);
    control.classList.add("listener");
  }
}

function refreshIndex() {
  refreshTunnelStatus();
  if (!control) { setTimeout(refreshIndex, 500); return; }
  bindToggle();
  if (!tunnelIndex.classList.contains("listener")) {initTunnelControl();}
}

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

function resizeIframe() {
  const isIframed = document.documentElement.classList.contains("iframed") || window.self !== window.top;
  if (isIframed) {
    setTimeout(() => {
      parent.postMessage({ action: 'resize', iframeId: 'i2ptunnelframe' }, location.origin);
    }, 100);
  }
}

function preloadImage(url) {
  const img = new Image();
  img.src = url;
}

if (toggle) bindToggle();
if (control) initTunnelControl();
setInterval(refreshIndex, 5000);

document.addEventListener("DOMContentLoaded", () => {
  countServices();
  if (theme === "dark") {
    preloadImage("/themes/console/dark/images/tunnelmanager.webp");
  }
});

window.addEventListener("load", function () {
  if (window.self !== window.top) { resizeIframe(); }
});
