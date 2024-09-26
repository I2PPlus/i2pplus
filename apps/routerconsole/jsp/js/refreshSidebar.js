/* I2P+ RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import {sectionToggler, countNewsItems} from "/js/sectionToggle.js";
import {stickySidebar} from "/js/stickySidebar.js";
import {onVisible} from "/js/onVisible.js";

"use strict";

let isDown = false;
let isDownTimer = null;

const advancedGeneral = document.getElementById("sb_advancedgeneral");
const badges = document.querySelectorAll(".badge, #tunnelCount, #newsCount");
const bandwidth = document.getElementById("sb_bandwidth");
const clock = document.getElementById("clock");
const cpuBar = document.getElementById("sb_CPUBar");
const digits = document.querySelectorAll(".digits");
const down = document.getElementById("down");
const downStatus = '<span id="down">Router is down</span>';
const general = document.getElementById("sb_general");
const graphStats = document.getElementById("sb_graphstats");
const internals = document.getElementById("sb_internals");
const localtunnels = document.getElementById("sb_localtunnels");
const localtunnelSummary = document.getElementById("localtunnelSummary");
const memBar = document.getElementById("sb_memoryBar");
const minigraph = document.getElementById("minigraph");
const netStatus = document.getElementById("sb_netstatus");
const newsCount = document.getElementById("newsCount");
const notice = document.getElementById("sb_notice");
const peers = document.getElementById("sb_peers");
const queue = document.getElementById("sb_queue");
const routerControl = document.getElementById("sb_routerControl");
const sb = document.querySelector("#sidebar");
const sectionTitle = document.querySelectorAll("#sidebar h3, #sidebar a");
const services = document.getElementById("sb_services");
const shortGeneral = document.getElementById("sb_shortgeneral");
const shutdownStatus = document.getElementById("sb_shutdownStatus");
const statusPanel = document.querySelectorAll("#sb_status,.sb_tunnelstatus,#sb_routerControl");
const tunnelBuildStatus = document.getElementById("sb_tunnelstatus");
const tunnelCount = document.getElementById("tunnelCount");
const tunnels = document.getElementById("sb_tunnels");
const updateBar = document.getElementById("sb_updatebar");
const updateForm = document.getElementById("sb_updateform");
const updateStatus = document.getElementById("sb_updateprogress");
const updateSection = document.getElementById("sb_updatesection");
const updateSectionHR = document.querySelector("#sb_updatesection + hr");
const visible = document.visibilityState;

sb.addEventListener("loaded", () => {
  window.requestAnimationFrame(sectionToggler);
  window.requestAnimationFrame(countNewsItems);
});

function tangoDown() {
  statusPanel.forEach(statusPanel => (statusPanel.classList.add("statusDown")));
  digits.forEach(digit => (digit.innerHTML = "---&nbsp;"));
  if (clock) {clock.textContent = "--:--:--";}
  if (graphStats) {graphStats.textContent = " --- / ---";}
  badges.forEach(badge => (badge.textContent = ""));
  localtunnelSummary.classList.add("statusDown");
  document.querySelector("body").classList.add("isDown");
  if (shutdownStatus) {
    shutdownStatus.setAttribute("hidden", "hidden");
  }
  if (localtunnels) {
    localtunnels.innerHTML = "<tr id=routerdown><td colspan=3 height=10></td></tr>";
    if (tunnelCount) {
      tunnelCount.innerHTML = "";
    }
  }
  if (localtunnelSummary) {
    localtunnelSummary.innerHTML = "<tr id=routerdown><td colspan=3 height=10></td></tr>";
  }
  isDown = true;
  onVisible(sb, () => {
    window.requestAnimationFrame(refreshSidebar);
  });
}

function refreshSidebar() {
  const xhrsb = new XMLHttpRequest();
  const uri = location.pathname;
  const xhrContainer = document.getElementById("xhr");
  xhrsb.open("GET", "/xhr1.jsp?requestURI=" + uri, true);
  xhrsb.responseType = "document";
  xhrsb.overrideMimeType("text/html");
  xhrsb.setRequestHeader("Accept", "text/html");

  xhrsb.onload = function () {
    if (xhrsb.status === 200) {
      isDown = false;
      if (isDownTimer !== null) {location.reload();}
      document.querySelector("body").classList.remove("isDown");
    } else if (xhrsb.status === 404 || xhrsb.status === 500) {
      window.requestAnimationFrame(tangoDown);
    } else {
      console.log("Unexpected status code: " + xhrsb.status);
    }

    const badgesResponse = xhrsb.responseXML.querySelectorAll(".badge");
    if (!xhrsb.responseXML || badgesResponse.length !== badges.length) {window.requestAnimationFrame(refreshAll);}

    const advancedGeneralResponse = xhrsb.responseXML.getElementById("sb_advancedgeneral");
    const bandwidthResponse = xhrsb.responseXML.getElementById("sb_bandwidth");
    const clockResponse = xhrsb.responseXML.getElementById("clock");
    const cpuBarResponse = xhrsb.responseXML.getElementById("sb_CPUBar");
    const generalResponse = xhrsb.responseXML.getElementById("sb_general");
    const graphStatsResponse = xhrsb.responseXML.getElementById("sb_graphstats");
    const internalsResponse = xhrsb.responseXML.getElementById("sb_internals");
    const localtunnelsResponse = xhrsb.responseXML.getElementById("sb_localtunnels");
    const memBarResponse = xhrsb.responseXML.getElementById("sb_memoryBar");
    const netStatusResponse = xhrsb.responseXML.getElementById("sb_netstatus");
    const noticeResponse = xhrsb.responseXML.getElementById("sb_notice");
    const peersResponse = xhrsb.responseXML.getElementById("sb_peers");
    const queueResponse = xhrsb.responseXML.getElementById("sb_queue");
    const routerControlResponse = xhrsb.responseXML.getElementById("sb_routerControl");
    const servicesResponse = xhrsb.responseXML.getElementById("sb_services");
    const shortGeneralResponse = xhrsb.responseXML.getElementById("sb_shortgeneral");
    const shutdownStatusResponse = xhrsb.responseXML.getElementById("sb_shutdownStatus");
    const tunnelBuildStatusResponse = xhrsb.responseXML.getElementById("sb_tunnelstatus");
    const tunnelsResponse = xhrsb.responseXML.getElementById("sb_tunnels");
    const updateFormResponse = xhrsb.responseXML.getElementById("sb_updateform");
    const updateSectionResponse = xhrsb.responseXML.getElementById("sb_updatesection");
    const updateStatusResponse = xhrsb.responseXML.getElementById("sb_updateprogress");
    const routerdown = document.getElementById("routerdown");

    statusPanel.forEach(statusPanel => (statusPanel.classList.remove("statusDown")));

    function updateVolatile() {
      window.requestAnimationFrame(uncollapse);

      if (clock && clockResponse && !Object.is(clock?.textContent, clockResponse?.textContent)) {
        clock.textContent = clockResponse.textContent;
      }
      if (badges && badgesResponse) {
        const badgesLen = badges.length;
        const badgesResponseLen = badgesResponse.length;
        for (let i = 0; i < badgesLen && i < badgesResponseLen; i++) {
          if (badges[i].textContent !== badgesResponse[i].textContent) {
            badges[i].textContent = badgesResponse[i].textContent;
          }
        }
      }
      if (netStatus && netStatusResponse && !Object.is(netStatus.innerHTML, netStatusResponse.innerHTML)) {
        netStatus?.replaceWith(netStatusResponse);
      }
      if (bandwidth) {
        if (bandwidthResponse && !Object.is(bandwidth.innerHTML, bandwidthResponse.innerHTML)) {
          bandwidth.innerHTML = bandwidthResponse.innerHTML;
        }
        if (graphStats) {
          if (graphStatsResponse && !Object.is(graphStats.textContent, graphStatsResponse.textContent)) {
            graphStats.innerHTML = graphStatsResponse.innerHTML;
          }
          if (bandwidth?.hasAttribute("hidden")) {
            graphStats.classList.add("show");
          } else {
            graphStats.classList.remove("show");
          }
        }
      }
      if (advancedGeneral && !advancedGeneral?.hasAttribute("hidden")) {
        if (advancedGeneralResponse && !Object.is(advancedGeneral.innerHTML, advancedGeneralResponse.innerHTML)) {
            advancedGeneral.innerHTML = advancedGeneralResponse.innerHTML;
        }
      }
      if (shortGeneral && shortGeneralResponse && !shortGeneral?.hasAttribute("hidden")) {
        if (!Object.is(shortGeneral.innerHTML, shortGeneralResponse.innerHTML)) {
          shortGeneral.innerHTML = shortGeneralResponse.innerHTML;
        }
      }
      if (general && generalResponse && !general?.hasAttribute("hidden")) {
        if (!Object.is(general.innerHTML, generalResponse.innerHTML)) {
          general.innerHTML = generalResponse.innerHTML;
        }
      }
      if (tunnels && tunnelsResponse && !tunnels?.hasAttribute("hidden")) {
        if (!Object.is(tunnels.innerHTML, tunnelsResponse.innerHTML)) {
          tunnels.innerHTML = tunnelsResponse.innerHTML;
        }
      }
      if (localtunnels) {
        if (localtunnelsResponse && !Object.is(localtunnels.innerHTML, localtunnelsResponse.innerHTML)) {
          localtunnels.innerHTML = localtunnelsResponse.innerHTML;
        }
      }
      if (localtunnelSummary) {
        const clients = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/client.svg"]').length;
        const clientSpan = '<span id=clientCount class="count_' + clients + '">' + clients + ' x <img src="/themes/console/images/client.svg"></span>';
        const i2pchats = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/i2pchat.svg"]').length;
        const i2pchatSpan = '<span id=i2pchatCount class="count_' + i2pchats + '">' + i2pchats + ' x <img src="/themes/console/images/i2pchat.svg"></span>';
        const pings = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/ping.svg"]').length;
        const pingSpan = '<span id=pingCount class="count_' + pings + '">' + pings + ' x <img src="/themes/console/images/ping.svg"></span>';
        const servers = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/server.svg"]').length;
        const serverSpan = '<span id=serverCount class="count_' + servers + '">' + servers + ' x <img src="/themes/console/images/server.svg"></span>';
        const snarks = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/snark.svg"]').length;
        const snarkSpan = '<span id=snarkCount class="count_' + snarks + '">' + snarks + ' x <img src="/themes/console/images/snark.svg"></span>';
        const summary = serverSpan + " " + clientSpan + " " + snarkSpan + " " + i2pchatSpan + " " + pingSpan;
        const summaryTable = '<tr id=localtunnelsActive><td>' + summary + '</td></tr>';
        const localtunnelsHeading = document.getElementById("sb_localTunnelsHeading");
        const localtunnelsHeadingResponse = xhrsb.responseXML.getElementById("sb_localTunnelsHeading");

        if (localtunnels && localtunnelsResponse && !Object.is(localtunnels.innerHTML, localtunnelsResponse.innerHTML)) {
          localtunnelsHeading.innerHTML = localtunnelsHeadingResponse.innerHTML;
          localtunnelSummary.innerHTML = summaryTable;
        }
      }
      if (tunnelCount) {
        const doubleCount = document.querySelector("#tunnelCount + #tunnelCount");
        if (doubleCount) {doubleCount.remove();}
      }
      if (peers && !peers?.hasAttribute("hidden")) {
        if (peersResponse && !Object.is(peers.innerHTML, peersResponse.innerHTML)) {
          peers.innerHTML = peersResponse.innerHTML;
        }
      }
      if (queue && !queue?.hasAttribute("hidden")) {
        if (queueResponse && !Object.is(queue.innerHTML, queueResponse.innerHTML)) {
          queue.innerHTML = queueResponse.innerHTML;
        }
      }
      if (memBar && memBarResponse && !Object.is(memBar.innerHTML, memBarResponse.innerHTML)) {
        memBar.innerHTML = memBarResponse.innerHTML;
      }
      if (cpuBar && cpuBarResponse && !Object.is(cpuBar.innerHTML, cpuBarResponse.innerHTML)) {
        cpuBar.innerHTML = cpuBarResponse.innerHTML;
      }
      const updateInProgress = document.querySelector(".sb_update.inProgress");
      if (updateInProgress) {
        const updateInProgressResponse = xhrsb.responseXML.querySelector(".sb_update.inProgress");
        if (updateInProgressResponse && updateInProgress.innerHTML !== updateInProgressResponse.innerHTML) {
          updateInProgress.innerHTML = updateInProgressResponse.innerHTML;
        }
      }
      if (updateSection && !updateSection?.classList.contains("collapsed")) {
        if (updateStatus && updateStatusResponse && !Object.is(updateStatus.innerHTML, updateStatusResponse.innerHTML)) {
          updateStatus.innerHTML = updateStatusResponse.innerHTML;
        }
      }
      if (updateForm && updateFormResponse && !Object.is(updateForm.innerHTML, updateFormResponse.innerHTML)) {
        updateForm.innerHTML = updateFormResponse.innerHTML;
        if (updateSectionHR.hidden === true) {updateSectionHR.hidden = null;}
      }
      if (updateStatus && updateStatusResponse && !Object.is(updateStatus.innerHTML, updateStatusResponse.innerHTML)) {
        updateStatus.innerHTML = updateStatusResponse.innerHTML;
      }
      if (updateSection && !updateSection?.hasAttribute("hidden")) {
        if (updateSectionResponse && !Object.is(updateSection.innerHTML, updateSectionResponse.innerHTML)) {
          updateSection.hidden = null;
          updateSection.outerHTML = updateSectionResponse.outerHTML;
        }
      }
      if (tunnelBuildStatus && tunnelBuildStatusResponse)
        if (tunnelBuildStatus?.classList.contains("statusDown") ||
            !Object.is(tunnelBuildStatus?.outerHTML, tunnelBuildStatusResponse?.outerHTML)) {
        tunnelBuildStatus.innerHTML = tunnelBuildStatusResponse.innerHTML;
      }
      if (notice && noticeResponse && !Object.is(notice.innerHTML, noticeResponse.innerHTML)) {
        notice.innerHTML = noticeResponse.innerHTML;
      } else if (noticeResponse && noticeResponse === null) {notice.remove();}
      if (shutdownStatus && shutdownStatusResponse && !Object.is(shutdownStatus.innerHTML, shutdownStatusResponse.innerHTML)) {
        shutdownStatus.innerHTML = shutdownStatusResponse.innerHTML;
      } else if (shutdownStatus && shutdownStatusResponse === null) {shutdownStatus.remove();}
      if (routerControl && routerControlResponse && routerControl?.classList.contains("statusDown") &&
          !Object.is(routerControl.innerHTML, routerControlResponse.innerHTML)) {
        routerControl.outerHTML = routerControlResponse.outerHTML;
      }
      if (internals && internalsResponse && !Object.is(internals.innerHTML, internalsResponse.innerHTML)) {
        internals.outerHTML = internalsResponse.outerHTML;
      }
      if (services && servicesResponse && !Object.is(services.innerHTML, servicesResponse.innerHTML)) {
        services.outerHTML = servicesResponse.outerHTML;
      }
    }

    function checkSections() {
      if (!xhrsb.responseXML) {return;}
      const updating = document.querySelectorAll("#xhr .volatile");
      const updatingLen = updating.length;
      const updatingResponse = xhrsb.responseXML.querySelectorAll("#sb .volatile");
      const updatingResponseLen = updatingResponse?.length;
      if (updatingLen !== updatingResponseLen) {
        window.requestAnimationFrame(refreshAll);
      } else {
        window.requestAnimationFrame(sectionToggler);
        window.requestAnimationFrame(updateVolatile);
        window.requestAnimationFrame(countNewsItems);
      }
    }
    checkSections();

    function refreshAll() {
      if (sb && xhrsb.responseXML) {
        const sbResponse = xhrsb.responseXML.getElementById("sb");
        if (sbResponse && !Object.is(sb.innerHTML, sbResponse.innerHTML)) {
          window.requestAnimationFrame(() => {
            xhrContainer.innerHTML = sbResponse.innerHTML;
          });
          window.requestAnimationFrame(sectionToggler);
          window.requestAnimationFrame(countNewsItems);
        }
      } else if ("requestIdleCallback" in window) {
        requestIdleCallback(tangoDown);
      } else {
        window.requestAnimationFrame(tangoDown);
      }
    }

    const graphCanvas = document.getElementById("minigraph");
    const ctx = graphCanvas ? graphCanvas.getContext("2d") : null;

    if (ctx) {
      ctx.imageSmoothingEnabled = false;
      ctx.globalCompositeOperation = "copy";
      ctx.globalAlpha = 1;
    }

    async function refreshGraph() {
      const graphContainer = document.getElementById("sb_graphcontainer");
      const graphContainerHR = document.querySelector("#sb_graphcontainer + hr");
      const minigraph = document.getElementById("minigraph");
      const minigraph_height = 50;
      const minigraph_width = 245;
      const image = new Image();

      if (graphContainer.hidden === true) {
        graphContainer.hidden = null;
        graphContainerHR.hidden = null;
      }

      const response = await fetch(`/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=${Date.now()}`);
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      image.src = url;

      return new Promise((resolve) => {
        image.onload = () => {
          minigraph.width = minigraph_width;
          minigraph.height = minigraph_height;
          if (ctx) {
            ctx.drawImage(image, 0, 0, minigraph_width, minigraph_height);
          }
          resolve();
        };
      });
    }

    if ("requestIdleCallback" in window) {
      requestIdleCallback(async () => {
        await refreshGraph();
      });
    } else {
      window.requestAnimationFrame(async () => {
        await refreshGraph();
      });
    }

    function uncollapse() {
      window.requestAnimationFrame(sectionToggler);
      window.requestAnimationFrame(countNewsItems);
    }

    const reseedform = document.getElementById("form_reseed");
    const sidebarform = document.getElementById("form_sidebar");
    const updatesform = document.getElementById("form_updates");
    if (reseedform) {
      reseedform.onsubmit = function() {handleFormSubmit();};
    } else if (sidebarform) {
      sidebarform.onsubmit = function() {handleFormSubmit();};
    } else if (updatesform) {
      updatesform.onsubmit = function() {handleFormSubmit();};
    }

    function handleFormSubmit() {
      setTimeout(refreshAll, 3000);
    }

  };

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

export {refreshSidebar};