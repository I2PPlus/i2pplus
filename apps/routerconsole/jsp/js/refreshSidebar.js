/* I2P+ RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { onVisible } from "/js/onVisible.js";

"use strict";

let isDown = false;
let isDownTimer = null;
const sb = document.querySelector("#sidebar");

const elements = {
  badges: sb.querySelectorAll(".badge, #tunnelCount, #newsCount"),
  bandwidth: document.getElementById("sb_bandwidth"),
  clock: document.getElementById("clock"),
  cpuBar: document.getElementById("sb_CPUBar"),
  digits: sb.querySelectorAll(".digits"),
  down: document.getElementById("down"),
  general: document.getElementById("sb_general"),
  graphStats: document.getElementById("sb_graphstats"),
  localtunnels: document.getElementById("sb_localtunnels"),
  localtunnelSummary: document.getElementById("localtunnelSummary"),
  memBar: document.getElementById("sb_memoryBar"),
  netStatus: document.getElementById("sb_netstatus"),
  newsCount: document.getElementById("newsCount"),
  notice: document.getElementById("sb_notice"),
  peers: document.getElementById("sb_peers"),
  queue: document.getElementById("sb_queue"),
  routerControl: document.getElementById("sb_routerControl"),
  sectionTitle: sb.querySelectorAll("#sidebar h3, #sidebar a"),
  services: document.getElementById("sb_services"),
  shortGeneral: document.getElementById("sb_shortgeneral"),
  shutdownStatus: document.getElementById("sb_shutdownStatus"),
  statusPanel: sb.querySelectorAll("#sb_status, .sb_tunnelstatus, #sb_routerControl"),
  tunnelBuildStatus: document.getElementById("sb_tunnelstatus"),
  tunnelCount: document.getElementById("tunnelCount"),
  tunnels: document.getElementById("sb_tunnels"),
  updateBar: document.getElementById("sb_updatebar"),
  updateForm: document.getElementById("sb_updateform"),
  updateStatus: document.getElementById("sb_updateprogress"),
  updateSection: document.getElementById("sb_updatesection"),
  updateSectionHR: sb.querySelector("#sb_updatesection+hr")
};

sb.addEventListener("loaded", () => { initSidebar(); });

function tangoDown() {
  elements.statusPanel.forEach(panel => panel.classList.add("statusDown"));
  elements.digits.forEach(digit => digit.innerHTML = "---&nbsp;");
  if (elements.clock) elements.clock.textContent = "--:--:--";
  if (elements.graphStats) elements.graphStats.textContent = " --- / ---";
  elements.badges.forEach(badge => badge.textContent = "");
  if (elements.localtunnelSummary) elements.localtunnelSummary.classList.add("statusDown");
  document.querySelector("body").classList.add("isDown");
  if (elements.shutdownStatus) elements.shutdownStatus.setAttribute("hidden", "hidden");
  if (elements.localtunnels) {
    elements.localtunnels.innerHTML = "<tr id=routerdown><td colspan=3 height=10></td></tr>";
    if (elements.tunnelCount) elements.tunnelCount.innerHTML = "";
  }
  if (elements.localtunnelSummary) {
    elements.localtunnelSummary.innerHTML = "<tr id=routerdown><td colspan=3 height=10></td></tr>";
  }
  isDown = true;
  onVisible(sb, () => requestIdleOrAnimationFrame(refreshSidebar));
}

function refreshSidebar() {
  const xhrsb = new XMLHttpRequest();
  const uri = location.pathname;
  const xhrContainer = document.getElementById("xhr");
  xhrsb.open("GET", `/xhr1.jsp?requestURI=${uri}`, true);
  xhrsb.responseType = "document";
  xhrsb.overrideMimeType("text/html");
  xhrsb.setRequestHeader("Accept", "text/html");

  xhrsb.onload = function () {
    if (xhrsb.status === 200) {
      isDown = false;
      if (isDownTimer !== null) location.reload();
      document.querySelector("body").classList.remove("isDown");
    } else if (xhrsb.status === 404 || xhrsb.status === 500) {requestIdleOrAnimationFrame(tangoDown);}

    const responseElements = {
      badges: xhrsb.responseXML.querySelectorAll(".badge"),
      advancedGeneral: xhrsb.responseXML.getElementById("sb_advancedgeneral"),
      bandwidth: xhrsb.responseXML.getElementById("sb_bandwidth"),
      clock: xhrsb.responseXML.getElementById("clock"),
      cpuBar: xhrsb.responseXML.getElementById("sb_CPUBar"),
      general: xhrsb.responseXML.getElementById("sb_general"),
      graphStats: xhrsb.responseXML.getElementById("sb_graphstats"),
      internals: xhrsb.responseXML.getElementById("sb_internals"),
      localtunnels: xhrsb.responseXML.getElementById("sb_localtunnels"),
      memBar: xhrsb.responseXML.getElementById("sb_memoryBar"),
      netStatus: xhrsb.responseXML.getElementById("sb_netstatus"),
      notice: xhrsb.responseXML.getElementById("sb_notice"),
      peers: xhrsb.responseXML.getElementById("sb_peers"),
      queue: xhrsb.responseXML.getElementById("sb_queue"),
      routerControl: xhrsb.responseXML.getElementById("sb_routerControl"),
      services: xhrsb.responseXML.getElementById("sb_services"),
      shortGeneral: xhrsb.responseXML.getElementById("sb_shortgeneral"),
      shutdownStatus: xhrsb.responseXML.getElementById("sb_shutdownStatus"),
      tunnelBuildStatus: xhrsb.responseXML.getElementById("sb_tunnelstatus"),
      tunnels: xhrsb.responseXML.getElementById("sb_tunnels"),
      updateForm: xhrsb.responseXML.getElementById("sb_updateform"),
      updateStatus: xhrsb.responseXML.getElementById("sb_updateprogress")
    };

    elements.statusPanel.forEach(panel => panel.classList.remove("statusDown"));

    function updateVolatile() {
      initSidebar();

      const updateElement = (elem, respElem) => {
        if (elem && respElem && elem.innerHTML !== respElem.innerHTML) {
          elem.innerHTML = respElem.innerHTML;
        }
      };

      const updateTextContent = (elem, respElem) => {
        if (elem && respElem && elem.textContent !== respElem.textContent) {
          elem.textContent = respElem.textContent;
        }
      };

      const updateIfNotHidden = (elem, respElem) => {
        if (elem && !elem.hasAttribute("hidden") && respElem && elem.innerHTML !== respElem.innerHTML) {
          elem.innerHTML = respElem.innerHTML;
        }
      };

      const updateIfNotCollapsed = (elem, respElem) => {
        if (elem && !elem.classList.contains("collapsed") && respElem && elem.innerHTML !== respElem.innerHTML) {
          elem.innerHTML = respElem.innerHTML;
        }
      };

      const updateIfNotRemoved = (elem, respElem) => {
        if (elem && respElem && elem.innerHTML !== respElem.innerHTML) {
          elem.innerHTML = respElem.innerHTML;
        } else if (elem && !respElem) {
          elem.remove();
        }
      };

      const updateIfStatusDown = (elem, respElem) => {
        if (elem && elem.classList.contains("statusDown") && respElem && elem.innerHTML !== respElem.innerHTML) {
          elem.outerHTML = respElem.outerHTML;
        }
      };

      updateTextContent(elements.clock, responseElements.clock);
      updateElement(elements.netStatus, responseElements.netStatus);
      updateElement(elements.bandwidth, responseElements.bandwidth);
      updateTextContent(elements.graphStats, responseElements.graphStats);
      updateIfNotHidden(elements.advancedGeneral, responseElements.advancedGeneral);
      updateIfNotHidden(elements.shortGeneral, responseElements.shortGeneral);
      updateIfNotHidden(elements.general, responseElements.general);
      updateIfNotHidden(elements.tunnels, responseElements.tunnels);
      updateElement(elements.localtunnels, responseElements.localtunnels);
      updateIfNotHidden(elements.tunnelCount, responseElements.tunnelCount);
      updateIfNotHidden(elements.peers, responseElements.peers);
      updateIfNotHidden(elements.queue, responseElements.queue);
      updateElement(elements.memBar, responseElements.memBar);
      updateElement(elements.cpuBar, responseElements.cpuBar);
      updateIfNotCollapsed(elements.updateSection, responseElements.updateSection);
      updateElement(elements.updateForm, responseElements.updateForm);
      updateElement(elements.updateStatus, responseElements.updateStatus);
      updateIfStatusDown(elements.tunnelBuildStatus, responseElements.tunnelBuildStatus);
      updateElement(elements.notice, responseElements.notice);
      updateIfNotRemoved(elements.shutdownStatus, responseElements.shutdownStatus);
      updateIfStatusDown(elements.routerControl, responseElements.routerControl);
      updateElement(elements.internals, responseElements.internals);
      updateElement(elements.services, responseElements.services);

      if (elements.badges && responseElements.badges) {
        for (let i = 0; i < Math.min(elements.badges.length, responseElements.badges.length); i++) {
          if (elements.badges[i].textContent !== responseElements.badges[i].textContent) {
            elements.badges[i].textContent = responseElements.badges[i].textContent;
          }
        }
      }

      const updateInProgress = sb.querySelector(".sb_update.inProgress");
      if (updateInProgress && responseElements.updateInProgress && updateInProgress.innerHTML !== responseElements.updateInProgress.innerHTML) {
        updateInProgress.innerHTML = responseElements.updateInProgress.innerHTML;
      }

      if (elements.updateForm && responseElements.updateForm && elements.updateForm.innerHTML !== responseElements.updateForm.innerHTML) {
        elements.updateForm.innerHTML = responseElements.updateForm.innerHTML;
        if (elements.updateSectionHR.hidden) elements.updateSectionHR.hidden = null;
      }

      const doubleCount = sb.querySelector("#tunnelCount + #tunnelCount");
      if (doubleCount) doubleCount.remove();
    }

    function updateLocalTunnels() {
      return new Promise((resolve) => {
        if (elements.localtunnelSummary) {
          const localtunnels = elements.localtunnels;
          const localtunnelsHeading = document.getElementById("sb_localTunnelsHeading");
          const localtunnelsHeadingResponse = responseElements.localtunnels?.querySelector("#sb_localTunnelsHeading");
          if (localtunnels && localtunnelsHeading && localtunnelsHeadingResponse && localtunnels.innerHTML !== responseElements.localtunnels.innerHTML) {
            const cachedLocalTunnelsHTML = localStorage.getItem('cachedLocalTunnelsHTML');
            let cachedData = null;
            if (cachedLocalTunnelsHTML) {cachedData = JSON.parse(cachedLocalTunnelsHTML);}
            const responseHTML = {
              headingHTML: localtunnelsHeadingResponse.innerHTML,
              summaryHTML: responseElements.localtunnels.innerHTML
            };
            if (cachedData && cachedData.headingHTML === responseHTML.headingHTML && cachedData.summaryHTML === responseHTML.summaryHTML) {
              localtunnelsHeading.innerHTML = cachedData.headingHTML;
              elements.localtunnelSummary.innerHTML = cachedData.summaryHTML;
            } else if (localtunnelsHeadingResponse.innerHTML && responseElements.localtunnels.innerHTML) {
              const counts = ["client", "i2pchat", "ping", "server", "snark"].reduce((acc, type) => {
                acc[type] = localtunnels.querySelectorAll(`img[src="/themes/console/images/${type}.svg"]`).length;
                return acc;
              }, {});

              counts.server -= counts.snark + counts.i2pchat;

              const summary = Object.keys(counts).map(type =>
                `<span id="${type}Count" class="count_${counts[type]}">${counts[type]} x <img src="/themes/console/images/${type}.svg"></span>`
              ).join(" ");

              const summaryTable = `<tr id="localtunnelsActive"><td>${summary}</td></tr>`;
              const fragment = document.createDocumentFragment();
              const headingElement = document.createElement('div');
              headingElement.innerHTML = localtunnelsHeadingResponse.innerHTML;
              fragment.appendChild(headingElement.firstChild);
              const summaryElement = document.createElement('div');
              summaryElement.innerHTML = summaryTable;
              fragment.appendChild(summaryElement.firstChild);
              localtunnelsHeading.innerHTML = '';
              elements.localtunnelSummary.innerHTML = '';
              localtunnelsHeading.appendChild(fragment.firstChild.cloneNode(true));
              elements.localtunnelSummary.appendChild(fragment.firstChild.cloneNode(true));
              const newLocalTunnelsHTML = {
                headingHTML: localtunnelsHeading.innerHTML,
                summaryHTML: elements.localtunnelSummary.innerHTML
              };
              localStorage.setItem('cachedLocalTunnelsHTML', JSON.stringify(newLocalTunnelsHTML));
            } else {localStorage.removeItem('cachedLocalTunnelsHTML');}
          }
        }
        resolve();
      });
    }

    (function checkSections() {
      if (!xhrsb.responseXML) return;
      const updating = sb.querySelectorAll("#xhr .volatile");
      const updatingResponse = xhrsb.responseXML.querySelectorAll("#sb .volatile");
      if (updating.length !== updatingResponse.length) {refreshAll();}
      else { updateLocalTunnels().then(() => { updateVolatile(); }); }
    })();

    function refreshAll() {
      if (sb && xhrsb.responseXML) {
        const sbResponse = xhrsb.responseXML.getElementById("sb");
        if (sbResponse && sb.innerHTML !== sbResponse.innerHTML) {
          requestIdleOrAnimationFrame(() => {
            xhrContainer.innerHTML = sbResponse.innerHTML;
            initSidebar();
          });
        }
      } else {requestIdleOrAnimationFrame(tangoDown);}
    }

    (() => {
      const graphCanvas = document.getElementById("minigraph");
      const ctx = graphCanvas?.getContext("2d");
      const graphContainer = document.getElementById("sb_graphcontainer");
      const graphContainerHR = document.querySelector("#sb_graphcontainer+hr");
      const [minigraphWidth, minigraphHeight] = [245, 50];
      if (ctx) { Object.assign(ctx, { imageSmoothingEnabled: false, globalCompositeOperation: "copy", globalAlpha: 1 }); }
      const refreshGraph = async () => {
        if (graphContainer && graphContainer.hidden) {graphContainer.hidden = graphContainerHR.hidden = false;}
        const response = await fetch(`/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=${Date.now()}`);
        if (!response.ok) {return;}
        const image = new Image(minigraphWidth, minigraphHeight);
        image.src = URL.createObjectURL(await response.blob());
        return new Promise((resolve) => {
          image.onload = () => {
            graphCanvas.width = minigraphWidth;
            graphCanvas.height = minigraphHeight;
            ctx?.drawImage(image, 0, 0);
            resolve();
          };
        });
      };
      const scheduleRefresh = async (callback) => { try {await callback();} catch {} };
      refreshGraph().then(() => { requestIdleOrAnimationFrame(() => scheduleRefresh(refreshGraph)); });
    })();

    async function initSidebar() {
      sectionToggler();
      await updateLocalTunnels();
      countNewsItems();
    }

    const forms = ["form_reseed", "form_sidebar", "form_updates"].map(id => document.getElementById(id)).filter(form => form);
    forms.forEach(form => form.onsubmit = () => handleFormSubmit());
    function handleFormSubmit() { setTimeout(refreshAll, 3000); }
  };

  function requestIdleOrAnimationFrame(callback) {
    if (typeof requestIdleCallback === "function") { requestIdleCallback(callback); }
    else { requestAnimationFrame(callback); }
  }

  function ready() {
    xhrsb.send();
    stickySidebar();
    isDown = false;
  }

  onVisible(sb, ready);

  xhrsb.onerror = function (error) {
    isDownTimer = setTimeout(tangoDown, 5000);
    isDown = true;
  };

}

export { refreshSidebar };