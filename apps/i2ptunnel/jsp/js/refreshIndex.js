/* I2P+ refreshIndex.js for I2PTunnel Manager by dr|z3d */
/* Periodically refresh the index and update status counts */
/* License: AGPL3 or later */

import { initToggleInfo } from "/i2ptunnel/js/toggleTunnelInfo.js";

const control = document.getElementById("globalTunnelControl");
const countClient = document.getElementById("countClient");
const countServer = document.getElementById("countServer");
const isDown = document.getElementById("down");
const messages = document.getElementById("tunnelMessages");
const notReady = document.getElementById("notReady");
const toggle = document.getElementById("toggleInfo");
const tunnelIndex = document.getElementById("page");
const url = window.location.pathname;

function updateElementContent(element, responseElement) {
  if (responseElement && element.innerHTML !== responseElement.innerHTML) {
    element.innerHTML = responseElement.innerHTML;
  }
}

async function refreshTunnelStatus() {
  try {
    const response = await fetch(url, { method: "GET", headers: { "Cache-Control": "no-cache" } });
    if (response.ok) {
      const doc = new DOMParser().parseFromString(await response.text(), "text/html");
      const downElement = document.getElementById("down");
      if (downElement) {
        downElement.remove();
        const pageFromResponse = doc.getElementById("page");
        if (pageFromResponse) { document.body.appendChild(pageFromResponse); }
      }

      if (isDown && doc.getElementById("globalTunnelControl")) { reloadPage(); }
      if (notReady) {
        const notReadyResponse = doc.getElementById("notReady");
        if (notReadyResponse) { refreshAll(doc); }
        else { reloadPage(); }
      } else { updateVolatile(doc); }

      countServices();
      resizeIframe();
    } else { handleDownState(); }
  } catch (error) { handleDownState(); }
}

function handleDownState() {
  if (!document.getElementById("down")) {
    const downElement = document.createElement("div");
    downElement.id = "down";
    downElement.className = "notReady";
    downElement.innerHTML = "<b><span>Router is down</span></b>";
    const styles = { position: "absolute", top: "50%", left: "50%", transform: "translate(-50%, -50%)" };
    Object.assign(downElement.style, styles);
    const tunnelIndex = document.getElementById("page");
    if (tunnelIndex) {tunnelIndex.replaceWith(downElement);}
    resizeIframe();
  } else { reloadPage(); }
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

if (toggle) bindToggle();
if (control) initTunnelControl();
setInterval(refreshIndex, 5000);
document.addEventListener("DOMContentLoaded", countServices);

document.querySelector("form").addEventListener("submit", async function (e) {
  e.preventDefault();

  const formData = new FormData(this);
  await fetch(this.action, {
    method: this.method,
    body: formData,
    headers: {
      "Cache-Control": "no-cache"
    }
  });

  await refreshTunnelStatus();
  resizeIframe();
});

window.addEventListener("load", function () {
  if (window.parentIFrame) {
    window.parentIFrame.size();
  }
});
