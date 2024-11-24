/* I2P+ RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import { sectionToggler, countNewsItems } from "/js/sectionToggle.js";
import { stickySidebar } from "/js/stickySidebar.js";
import { onVisible } from "/js/onVisible.js";

"use strict";

let isDown = false;
let isDownTimer = null;
let refreshTimeout;
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

const requestIdleOrAnimationFrame = (callback, timeout = 180) => {
  return new Promise(resolve => {
    let id;
    if (typeof requestIdleCallback === "function") {
      id = setTimeout(() => {
        cancelIdleCallback(id);
        requestAnimationFrame(() => {
          callback();
          resolve();
        });
      }, timeout);
      requestIdleCallback(() => {
        cancelIdleCallback(id);
        callback();
        resolve();
      });
    } else {
      setTimeout(() => {
        requestAnimationFrame(() => {
          callback();
          resolve();
        });
      }, timeout);
    }
  });
};

function tangoDown() {
  elements.statusPanel.forEach(panel => panel.classList.add("statusDown"));
  elements.digits.forEach(digit => digit.innerHTML = "---&nbsp;");
  if (elements.clock) elements.clock.textContent = "--:--:--";
  if (elements.graphStats) elements.graphStats.textContent = " --- / ---";
  elements.badges.forEach(badge => badge.textContent = "");
  ["localtunnelSummary", "shutdownStatus"].forEach(section => {
    if (elements[section]) {
      if (section === "localtunnelSummary") { elements[section].innerHTML = "<tr id=routerdown><td colspan=3 height=10 style=padding:0></td></tr>"; }
      elements[section].classList.add("statusDown");
    }
  });
  if (elements.localtunnels) {
    elements.localtunnels.innerHTML = "<tr id=routerdown><td colspan=3 height=10></td></tr>";
    if (elements.tunnelCount) elements.tunnelCount.innerHTML = "";
  }
  document.body.classList.add("isDown");
  isDown = true;
  onVisible(sb, () => requestIdleOrAnimationFrame(refreshSidebar));
}

async function refreshSidebar() {
  const uri = location.pathname;
  const xhrContainer = document.getElementById("xhr");

  try {
    const response = await fetch(`/xhr1.jsp?requestURI=${uri}`, { method: 'GET', headers: { 'Accept': 'text/html' } });
    if (response.ok) {
      isDown = false;
      const responseText = await response.text(), parser = new DOMParser(), responseDoc = parser.parseFromString(responseText, "text/html");

      if (isDownTimer !== null) location.reload();
      document.body.classList.remove("isDown");
      if (refreshTimeout) clearTimeout(refreshTimeout);
      refreshTimeout = setTimeout(refreshSidebar, refreshInterval);

      const responseElements = {
        badges: responseDoc.querySelectorAll(".badge"),
        advancedGeneral: responseDoc.getElementById("sb_advancedgeneral"),
        bandwidth: responseDoc.getElementById("sb_bandwidth"),
        clock: responseDoc.getElementById("clock"),
        cpuBar: responseDoc.getElementById("sb_CPUBar"),
        general: responseDoc.getElementById("sb_general"),
        graphStats: responseDoc.getElementById("sb_graphstats"),
        internals: responseDoc.getElementById("sb_internals"),
        localtunnels: responseDoc.getElementById("sb_localtunnels"),
        memBar: responseDoc.getElementById("sb_memoryBar"),
        netStatus: responseDoc.getElementById("sb_netstatus"),
        notice: responseDoc.getElementById("sb_notice"),
        peers: responseDoc.getElementById("sb_peers"),
        queue: responseDoc.getElementById("sb_queue"),
        routerControl: responseDoc.getElementById("sb_routerControl"),
        services: responseDoc.getElementById("sb_services"),
        shortGeneral: responseDoc.getElementById("sb_shortgeneral"),
        shutdownStatus: responseDoc.getElementById("sb_shutdownStatus"),
        tunnelBuildStatus: responseDoc.getElementById("sb_tunnelstatus"),
        tunnels: responseDoc.getElementById("sb_tunnels"),
        updateForm: responseDoc.getElementById("sb_updateform"),
        updateStatus: responseDoc.getElementById("sb_updateprogress")
      };

      elements.statusPanel.forEach(panel => panel.classList.remove("statusDown"));

      function updateElementInnerHTML(elem, respElem) {
        if (elem && respElem && elem.innerHTML !== respElem.innerHTML) {
          elem.innerHTML = respElem.innerHTML;
        }
      }

      function updateElementTextContent(elem, respElem) {
        if (elem && respElem && elem.textContent !== respElem.textContent) {
          elem.textContent = respElem.textContent;
        }
      }

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

      const updateVolatile = () => {
        initSidebar();

        updateElementTextContent(elements.clock, responseElements.clock);
        updateElementInnerHTML(elements.netStatus, responseElements.netStatus);
        updateElementInnerHTML(elements.bandwidth, responseElements.bandwidth);
        updateElementTextContent(elements.graphStats, responseElements.graphStats);
        updateIfNotHidden(elements.advancedGeneral, responseElements.advancedGeneral);
        updateIfNotHidden(elements.shortGeneral, responseElements.shortGeneral);
        updateIfNotHidden(elements.general, responseElements.general);
        updateIfNotHidden(elements.tunnels, responseElements.tunnels);
        updateElementInnerHTML(elements.localtunnels, responseElements.localtunnels);
        updateIfNotHidden(elements.tunnelCount, responseElements.tunnelCount);
        updateIfNotHidden(elements.peers, responseElements.peers);
        updateIfNotHidden(elements.queue, responseElements.queue);
        updateElementInnerHTML(elements.memBar, responseElements.memBar);
        updateElementInnerHTML(elements.cpuBar, responseElements.cpuBar);
        updateIfNotCollapsed(elements.updateSection, responseElements.updateSection);
        updateElementInnerHTML(elements.updateForm, responseElements.updateForm);
        updateElementInnerHTML(elements.updateStatus, responseElements.updateStatus);
        updateIfStatusDown(elements.tunnelBuildStatus, responseElements.tunnelBuildStatus);
        updateElementInnerHTML(elements.notice, responseElements.notice);
        updateIfNotRemoved(elements.shutdownStatus, responseElements.shutdownStatus);
        updateIfStatusDown(elements.routerControl, responseElements.routerControl);
        updateElementInnerHTML(elements.internals, responseElements.internals);
        updateElementInnerHTML(elements.services, responseElements.services);

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
        }).catch(() => {});
      }

      (function checkSections() {
        const updating = sb.querySelectorAll("#xhr .volatile");
        const updatingResponse = responseDoc.querySelectorAll("#sb .volatile");
        if (updating.length !== updatingResponse.length) {refreshAll();}
        else { updateLocalTunnels().then(() => { updateVolatile(); }); }
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
        } else {requestIdleOrAnimationFrame(tangoDown, Math.min(refreshInterval*2, 10*1000));}
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
            image.onerror = () => {reject();};
          }).catch(() => {});
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
    }
  } catch (error) {
    isDown = true;
    await new Promise(resolve => setTimeout(resolve, 3000));
    await tangoDown();
  }
}

function ready() {
  refreshSidebar().then(() => {stickySidebar();})
  .catch(error => {isDown = true;})
  .finally(() => { if (isDown) {setTimeout(tangoDown, 3000);} });
}

onVisible(sb, ready);

export { refreshSidebar };