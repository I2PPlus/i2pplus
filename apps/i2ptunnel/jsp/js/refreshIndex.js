/* I2P+ refreshIndex.js for I2PTunnel Manager by dr|z3d */
/* Periodically refresh the index and update status counts */
/* License: AGPL3 or later */

import { initToggleInfo } from "/i2ptunnel/js/toggleTunnelInfo.js";

const control = document.getElementById("globalTunnelControl");
const countClient = document.getElementById("countClient");
const countServer = document.getElementById("countServer");
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
  const isDown = document.getElementById("down");
  try {
    const response = await fetch(url, { method: "GET", headers: { "Cache-Control": "no-cache" } });
    if (response.ok) {
      const doc = new DOMParser().parseFromString(await response.text(), "text/html");
      if (isDown && doc.getElementById("globalTunnelControl")) reloadPage();
      if (notReady) {
        const notReadyResponse = doc.getElementById("notReady");
        if (notReadyResponse) {refreshAll(doc);}
        else {reloadPage();}
      } else {updateVolatile(doc);}
    } else {
      setTimeout(() => {
        tunnelIndex.innerHTML = "<div id='down' class='notReady'><b><span>Router is down</span></b></div>";
        if (isDown && doc.getElementById("globalTunnelControl")) reloadPage();
      }, 10000);
    }
  } catch {}
  countServices();
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
  else {updating.forEach((el, i) => updateElementContent(el, updatingResponse[i]));}
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
  if (!control) {
    setTimeout(refreshIndex, 500);
    return;
  }
  bindToggle();
  if (!tunnelIndex.classList.contains("listener")) {initTunnelControl();}
}

function initTunnelControl() {
  if (!tunnelIndex.classList.contains("listener")) {
    tunnelIndex.addEventListener("click", async event => {
      const target = event.target;
      if (target.classList.contains("control")) {
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

if (toggle) bindToggle();
if (control) initTunnelControl();
setInterval(refreshIndex, 5000);
document.addEventListener("DOMContentLoaded", countServices);