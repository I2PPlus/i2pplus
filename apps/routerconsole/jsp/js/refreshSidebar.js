/* RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import {sectionToggler, countTunnels} from "/js/sectionToggle.js";

function refreshSidebar() {
  'use strict';
  var pageVisibility = document.visibilityState;
  var meta = document.querySelector('[http-equiv="refresh"]');
  var xhr = new XMLHttpRequest();
  var uri = location.pathname.substring(1);
  var xhrContainer = document.getElementById("xhr");

  var advancedGeneral = document.getElementById("sb_advancedgeneral");
  var badges = document.querySelectorAll("h3 a .badge");
  var bandwidth = document.getElementById("sb_bandwidth");
  var clock = document.getElementById("clock");
  var down = document.getElementById("down");
  var general = document.getElementById("sb_general");
  var graphStats = document.getElementById("sb_graphstats");
  var localTunnels = document.getElementById("sb_localtunnels");
  var memBar = document.getElementById("sb_memoryBar");
  var netStatus = document.getElementById("sb_status");
  var notice = document.getElementById("sb_notice");
  var peers = document.getElementById("sb_peers");
  var queue = document.getElementById("sb_queue");
  var routerControl = document.getElementById("sb_routerControl");
  var sb = document.querySelector("#sidebar");
  var shortGeneral = document.getElementById("sb_shortgeneral");
  var shutdownStatus = document.getElementById("sb_shutdownStatus");
  var tunnelBuildStatus = document.getElementById("sb_tunnelstatus");
  var tunnelCount = document.getElementById("tunnelCount");
  var tunnels = document.getElementById("sb_tunnels");
  var updateBar = document.querySelector(".sb_updatestatus + .percentBarOuter");
  var updateForm = document.getElementById("sb_updateform");
  var updateProgress = document.getElementById("sb_updateprogress");
  var updateSection = document.getElementById("sb_updatesection");
  var updateSectionHR = document.querySelector("#sb_updatesection + hr");
  var updateStatus = document.getElementById("sb_updatestatus");

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
          var advancedGeneralResponse = xhr.responseXML.getElementById("sb_advancedgeneral");
          var badgesResponse = xhr.responseXML.querySelectorAll("h3 a .badge");
          var bandwidthResponse = xhr.responseXML.getElementById("sb_bandwidth");
          var clockResponse = xhr.responseXML.getElementById("clock");
          var generalResponse = xhr.responseXML.getElementById("sb_general");
          var graphStatsResponse = xhr.responseXML.getElementById("sb_graphstats");
          var localTunnelsResponse = xhr.responseXML.getElementById("sb_localtunnels");
          var memBarResponse = xhr.responseXML.getElementById("sb_memoryBar");
          var netStatusResponse = xhr.responseXML.querySelector("sb_netstatus");
          var noticeResponse = xhr.responseXML.getElementById("sb_notice");
          var peersResponse = xhr.responseXML.getElementById("sb_peers");
          var queueResponse = xhr.responseXML.getElementById("sb_queue");
          var routerControlResponse = xhr.responseXML.getElementById("sb_routerControl");
          var sbResponse = xhr.responseXML.getElementById("sb");
          var shortGeneralResponse = xhr.responseXML.getElementById("sb_shortgeneral");
          var shutdownStatusResponse = xhr.responseXML.getElementById("sb_shutdownstatus");
          var tunnelBuildStatusResponse = xhr.responseXML.getElementById("sb_tunnelstatus");
          var tunnelsResponse = xhr.responseXML.getElementById("sb_tunnels");
          var updateBarResponse = xhr.responseXML.querySelector(".sb_updatestatus + .percentBarOuter");
          var updateFormResponse = xhr.responseXML.getElementById("sb_updateform");
          var updateProgressResponse = xhr.responseXML.getElementById("sb_updateprogress");
          var updateSectionResponse = xhr.responseXML.getElementById("sb_updatesection");
          var updateStatusResponse = xhr.responseXML.getElementById("sb_updatestatus");
        }

        if (down) {
          uncollapse();
          refreshAll();
          countTunnels();
        }

        function updateVolatile(timestamp) {

          //uncollapse();
          sectionToggler();

          var b;
          for (b = 0; b < badges.length; b += 1) {
            if (typeof badges[b] !== "undefined") {
              badges[b].innerHTML = badgesResponse[b].innerHTML;
              if (badges.length !== badgesResponse.length) {
                window.requestAnimationFrame(refreshAll);
              }
            }
          }

          countTunnels();

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
            if (updateBarResponse != undefined) {
              updateBar.outerHTML = updateBarResponse.outerHTML;
            } else {
              window.requestAnimationFrame(refreshAll);
            }
          }
          if (updateStatus != undefined && !Object.is(updateStatus.innerHTML, updateStatusResponse.innerHTML)) {
            updateStatus.outerHTML = updateStatusResponse.outerHTML;
          }
          if (updateSection != undefined && updateSection.classList.contains("hide") != true) {
            updateSection.outerHTML = updateSectionResponse.outerHTML;
            if (updateSectionHR.hidden == true) {
              updateSectionHR.hidden = null;
            }
          }
          if (updateForm != undefined && updateForm.hidden != true && !Object.is(updateForm.innerHTML, updateFormResponse.innerHTML)) {
            updateForm.outerHTML = updateFormResponse.outerHTML;
            if (updateSectionHR.hidden == true) {
              updateSectionHR.hidden = null;
            }
          }
          if (updateProgress != undefined && !Object.is(updateprogress.innerHTML, updateprogressResponse.innerHTML)) {
            updateProgress.innerHTML = updateProgressResponse.innerHTML;
          }
          if (tunnelBuildStatus != undefined && !Object.is(tunnelBuildStatus.outerHTML, tunnelBuildStatusResponse.outerHTML)) {
            tunnelBuildStatus.innerHTML = tunnelBuildStatusResponse.innerHTML;
          }
          if (notice != undefined && !Object.is(notice.innerHTML, noticeResponse.innerHTML)) {
            notice.innerHTML = noticeResponse.innerHTML;
          }
          if (shutdownStatus != undefined && !Object.is(shutdownStatus.innerHTML, shutdownStatusResponse.innerHTML)) {
            shutdownStatus.innerHTML = shutdownStatusResponse.innerHTML;
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
          countTunnels();
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
          if (meta != null) {
            meta.remove();
          }
        }

        if (pageVisibility == "visible") {
          if (meta != null) {
            removeMeta();
          }
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
            if (meta != null) {
              removeMeta();
            }
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
            if (shutdownStatus != undefined) {
              shutdownStatus.setAttribute("hidden", "");
            }
            if (localTunnels) {
              localTunnels.innerHTML = '<tr id="routerdown"><td colspan="3"></td></tr>';
              if (tunnelCount != undefined) {
                tunnelCount.innerHTML = "-";
              }
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
            var badges = document.querySelectorAll("#sidebar h3 a .badge");
            var b;
            for (b = 0; b < badges.length; b += 1) {
              if (typeof badges[b] !== "undefined") {
                badges[b].innerHTML = "-";
              }
            }
            netStatus.innerHTML = '<span id="down">Router is down</span>';
          }

          hideSections();
          modElements();
        }
        setTimeout(isDown, 2950);
      }
    }
  };

  xhr.addEventListener("loaded", () => {
    sectionToggler();
    countTunnels();
  });
  xhr.send();

}

export {refreshSidebar};