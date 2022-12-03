/* RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import {sectionToggler} from "/js/sectionToggle.js";

function refreshSidebar() {
  'use strict';
  var pageVisibility = document.visibilityState;
  var xhr = new XMLHttpRequest();
  var uri = location.pathname.substring(1);
  var xhrContainer = document.getElementById("xhr");

  var down = document.getElementById("down");
  var localtunnels = document.getElementById("sb_localtunnels");
  var netstatus = document.getElementById("sb_status");
  var sb = document.querySelector("#sidebar");
  var shutdownstatus = document.getElementById("sb_shutdownStatus");

  xhr.open("GET", "/xhr1.jsp?requestURI=" + uri + "&t=" + new Date().getTime(), true);
  xhr.responseType = "document";
  xhr.overrideMimeType("text/html");
  xhr.setRequestHeader("Accept", "text/html");
  xhr.setRequestHeader("Cache-Control", "no-store, max-age=60");
  xhr.setRequestHeader("Content-Security-Policy",
    "default-src 'self'; style-src 'none'; script-src 'self'; frame-ancestors 'none'; object-src 'none'; media-src 'none'; base-uri 'self'"
  );

  xhr.onreadystatechange = function () {
    if (xhr.readyState == 4) {
      if (xhr.status == 200) {
        xhr.addEventListener("loaded", sectionToggler);
        if (xhr.reponseType !== "undefined") {
          var sbResponse = xhr.responseXML.getElementById("sb");
          var xhrDOM = new DOMParser().parseFromString(xhr.sbResponse, "text/html");
        }

        if (down) {
          uncollapse();
          refreshAll();
        }

        function updateVolatile(timestamp) {
          //uncollapse();
          sectionToggler();
          var clock = document.getElementById("clock");
          var clockResponse = xhr.responseXML.getElementById("clock");
          var bandwidth = document.getElementById("sb_bandwidth");
          var bandwidthResponse = xhr.responseXML.getElementById("sb_bandwidth");
          var graphStats = document.getElementById("sb_graphstats");
          var graphStatsResponse = xhr.responseXML.getElementById("sb_graphstats");
          var advancedGeneral = document.getElementById("sb_advancedgeneral");
          var advancedGeneralResponse = xhr.responseXML.getElementById("sb_advancedgeneral");
          var shortGeneral = document.getElementById("sb_shortgeneral");
          var shortGeneralResponse = xhr.responseXML.getElementById("sb_shortgeneral");
          var general = document.getElementById("sb_general");
          var generalResponse = xhr.responseXML.getElementById("sb_general");
          var tunnels = document.getElementById("sb_tunnels");
          var tunnelsResponse = xhr.responseXML.getElementById("sb_tunnels");
          var localTunnels = document.getElementById("sb_localtunnels");
          var localTunnelsResponse = xhr.responseXML.getElementById("sb_localtunnels");
          var tunnelCount = document.getElementById("tunnelCount");
          var peers = document.getElementById("sb_peers");
          var peersResponse = xhr.responseXML.getElementById("sb_peers");
          var queue = document.getElementById("sb_queue");
          var queueResponse = xhr.responseXML.getElementById("sb_queue");
          var memBar = document.getElementById("sb_memoryBar");
          var memBarResponse = xhr.responseXML.getElementById("sb_memoryBar");
          var updateBar = document.querySelector(".sb_updatestatus + .percentBarOuter");
          var updateBarResponse = xhr.responseXML.querySelector(".sb_updatestatus + .percentBarOuter");
          var routerControl = document.getElementById("sb_routerControl");
          var routerControlResponse = xhr.responseXML.getElementById("sb_routerControl");
          var updateSection = document.getElementById("sb_updatesection");
          var updateSectionHR = document.querySelector("#sb_updatesection + hr");
          var updateForm = document.getElementById("sb_updateform");
          var updateFormResponse = xhr.responseXML.getElementById("sb_updateform");
          var updateStatus = document.getElementById("sb_updatestatus");
          var updateStatusResponse = xhr.responseXML.getElementById("sb_updatestatus");
          var netStatus = document.querySelector("sb_netstatus");
          var netStatusResponse = xhr.responseXML.querySelector("sb_netstatus");
          var tunnelStatus = document.getElementById("sb_tunnelstatus");
          var tunnelStatusResponse = xhr.responseXML.getElementById("sb_tunnelstatus");
          var notice = document.getElementById("sb_notice");
          var noticeResponse = xhr.responseXML.getElementById("sb_notice");
          var shutdownStatus = document.getElementById("sb_shutdownstatus");
          var shutdownStatusResponse = xhr.responseXML.getElementById("sb_shutdownstatus");
          var badges = document.querySelectorAll("h3 a .badge");
          var badgesResponse = xhr.responseXML.querySelectorAll("h3 a .badge");

          var b;
          for (b = 0; b < badges.length; b += 1) {
            if (typeof badges[b] !== "undefined" && typeof badgesResponse[b] !== "undefined") {
              badges[b].innerHTML = badgesResponse[b].innerHTML;
              if (badges.length !== badgesResponse.length) {
                window.requestAnimationFrame(refreshAll);
              }
            }
          }

          if (clock != undefined && !Object.is(clock.innerHTML, clockResponse.innerHTML)) {
            clock.outerHTML = clockResponse.outerHTML;
          }
          if (bandwidth != undefined && bandwidth.hidden != true && !Object.is(bandwidth.innerHTML, bandwidthResponse.innerHTML)) {
            bandwidth.outerHTML = bandwidthResponse.outerHTML;
            graphStats.innerHTML = graphStats.innerHTML;
            graphStats.style.opacity = null;
          } else if (bandwidth.hidden == true) {
            graphStats.innerHTML = graphStatsResponse.innerHTML;
            graphStats.style.opacity = "1";
          }
          if (advancedGeneral != undefined && advancedGeneral.hidden != true && !Object.is(advancedGeneral.innerHTML, advancedGeneralResponse.innerHTML)) {
            advancedGeneral.outerHTML = advancedGeneralResponse.outerHTML;
          }
          if (shortGeneral != undefined && shortGeneral.hidden != true && !Object.is(shortGeneral.innerHTML, shortGeneralResponse.innerHTML)) {
            shortGeneral.outerHTML = shortGeneralResponse.outerHTML;
          }
          if (general != undefined && general.hidden != true && !Object.is(general.innerHTML, generalResponse.innerHTML)) {
            general.outerHTML = generalResponse.outerHTML;
          }
          if (tunnels != undefined && tunnels.hidden != true && !Object.is(tunnels.innerHTML, tunnelsResponse.innerHTML)) {
            tunnels.outerHTML = tunnelsResponse.outerHTML;
          }
          if (localTunnels != undefined && localTunnels.hidden != true && !Object.is(localTunnels.innerHTML, localTunnelsResponse.innerHTML)) {
            localTunnels.outerHTML = localTunnelsResponse.outerHTML;
            if (tunnelCount) {
              var doubleCount = document.querySelector("#tunnelCount + #tunnelCount");
              if (doubleCount) {
                doubleCount.remove();
              }
            }
          }
          if (peers != undefined && peers.hidden != true && !Object.is(peers.innerHTML, peersResponse.innerHTML)) {
            peers.outerHTML = peersResponse.outerHTML;
          }
          if (queue != undefined && queue.hidden != true && !Object.is(queue.innerHTML, queueResponse.innerHTML)) {
            queue.outerHTML = queueResponse.outerHTML;
          }
          if (memBar != undefined && !Object.is(memBar.innerHTML, memBarResponse.innerHTML)) {
            memBar.outerHTML = memBarResponse.outerHTML;
          }
          if (updateBar != undefined && !Object.is(updateBar.innerHTML, updateBarResponse.innerHTML)) {
            updateBar.outerHTML = updateBarResponse.outerHTML;
          }
          if (updateStatus != undefined && !Object.is(updateStatus.innerHTML, updateStatusResponse.innerHTML)) {
            updateStatus.outerHTML = updateStatusResponse.outerHTML;
          }
          if (tunnelStatus != undefined && !Object.is(tunnelStatus.innerHTML, tunnelStatusResponse.innerHTML)) {
            tunnelStatus.outerHTML = tunnelStatusResponse.outerHTML;
          }
          if (notice != undefined && !Object.is(notice.innerHTML, noticeResponse.innerHTML)) {
            notice.innerHTML = noticeResponse.innerHTML;
          }
          if (shutdownStatus != undefined && !Object.is(shutdownStatus.innerHTML, shutdownStatusResponse.innerHTML)) {
            shutdownStatus.innerHTML = shutdownStatusResponse.innerHTML;
          }
          if (updateSection != undefined && updateForm != undefined && updateForm.hidden != true && !Object.is(updateForm.innerHTML, updateFormResponse.innerHTML)) {
            updateForm.innerHTML = updateFormResponse.innerHTML;
            if (updateSectionHR.hidden == true) {
              updateSectionHR.hidden = null;
            }
          } else {
            if (updateSection != undefined && !Object.is(updateSection.innerHTML, updateSectionResponse.innerHTML)) {
              updateSection.innerHTML = updateSectionResponse.innerHTML;
              if (updateSectionHR.hidden == true) {
                updateSectionHR.hidden = null;
             }
            }
          }
          if (routerControl != undefined && !Object.is(routerControl.innerHTML, routerControlResponse.innerHTML)) {
            routerControl.outerHTML = routerControlResponse.outerHTML;
          }

          var updating = document.querySelectorAll(".volatile");
          var updatingResponse = xhr.responseXML.querySelectorAll(".volatile");
          var i;
          for (i = 0; i < updating.length; i += 1) {
            if (typeof updating[i] !== "undefined" && typeof updatingResponse[i] !== "undefined") {
              if (updating.length !== updatingResponse.length) {
                  window.requestAnimationFrame(refreshAll);
              }
            }
          }
        }

        function refreshAll(timestamp) {
          if (typeof sbResponse !== "undefined" && !Object.is(sb.innerHTML, sbResponse.innerHTML)) {
            xhrContainer.innerHTML = sbResponse.innerHTML;
          }
          sectionToggler();
        }

        function refreshGraph(timestamp) {
          var minigraph = document.getElementById("minigraph");
          if (minigraph) {
            const ctx = minigraph.getContext("2d");
            const image = new Image(245, 50);
            image.onload = renderGraph;
            image.src = "/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=" + new Date().getTime();
            ctx.imageSmoothingEnabled = true;
            ctx.imageSmoothingQuality = "high";
            ctx.globalCompositeOperation = "source-out";
            ctx.globalAlpha = 1;
            //minigraph.style.background = image.src;

            function renderGraph() {
              minigraph.width = 245;
              minigraph.height = 50;
              ctx.drawImage(this, 0, 0, 245, 50);
            }
          }
        }

        function uncollapse() {
          sectionToggler();
          var sectionTitle = document.querySelectorAll("#sidebar h3, #sidebar a");
          var a;
          for (a = 1; a < sectionTitle.length - 1; a += 1) {
            var styleInline = sectionTitle[a].getAttribute("style");
            if (styleInline) {
              sectionTitle[a].removeAttribute("style");
            }
          }

          var collapsed = document.querySelectorAll("#sidebar .volatile.collapse, #sidebar .volatile.collapse + hr");
          var c;
          for (c = 0; c < collapsed.length; c += 1) {
            var styleHidden = collapsed[c].getAttribute("hidden");
            if (styleHidden) {
              collapsed[c].removeAttribute("hidden");
            }
          }
        }

        function removeMeta() {
          var meta = document.querySelector('[http-equiv="refresh"]');
          if (meta != null) {
            meta.remove();
          }
        }

        if (pageVisibility == "visible") {
          removeMeta();
          //window.requestAnimationFrame(updateVolatile);
          updateVolatile();

          var minigraph = document.getElementById("minigraph");
          if (minigraph) {
            window.requestAnimationFrame(refreshGraph);
            var minigraphResponse = xhr.responseXML.getElementById("minigraph");
            minigraph = minigraphResponse;
          }

        } else if (xhr.readyState == 4 && xhr.status == 200) {

          setTimeout(function() {
            removeMeta();
            var metarefresh = document.createElement("meta");
            metarefresh.httpEquiv = "refresh";
            metarefresh.content = "1800";
            document.head.appendChild(metarefresh);
          }, 120000);
        }

      } else {

        function isDown() {
          function hideSections() {
            var collapse = document.querySelectorAll("#sidebar .collapse");
            var h;
            for (h = 0; h < collapse.length; h += 1) {
              collapse[h].setAttribute("hidden", "");
              if (collapse[h].nextElementSibling != null && collapse[h].nextElementSibling.nodeName == "HR") {
                collapse[h].nextElementSibling.setAttribute("hidden", "");
              }
            }
            if (shutdownstatus) {
              shutdownstatus.setAttribute("hidden", "");
            }
            if (localtunnels) {
              localtunnels.innerHTML = '<tr id="routerdown"><td colspan="3"></td></tr>';
            }
          }

          function modElements() {
            var sectionTitle = document.querySelectorAll("#sidebar h3, #sidebar a");
            var a;
            for (a = 1; a < sectionTitle.length - 1; a += 1) {
              sectionTitle[a].setAttribute("style", "pointer-events: none");
            }
            var digits = document.querySelectorAll(".digits");
            var i;
            for (i = 0; i < digits.length; i += 1) {
              digits[i].innerHTML = "---&nbsp;";
            }
            var clock = document.querySelector("#clock");
            if (clock != null) {
              clock.innerHTML = "--:--:--";
            }
            var badges = document.querySelectorAll("h3 a .badge");
            var b;
            for (b = 0; i < badges.length; b += 1) {
              if (typeof badges[b] !== "undefined") {
              badges[b].innerHTML = "-";
              }
            }
            netstatus.innerHTML = '<span id="down">Router is down</span>';
          }

          hideSections();
          modElements();
        }
        setTimeout(isDown, 2950);
      }
    }
  };
  xhr.addEventListener("loaded", sectionToggler);
  xhr.send();

}

export {refreshSidebar};