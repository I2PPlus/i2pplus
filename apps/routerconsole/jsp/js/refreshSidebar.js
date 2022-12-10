/* RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

import {sectionToggler, countTunnels, countNewsItems} from "/js/sectionToggle.js";

function refreshSidebar() {
  'use strict';
  var meta = document.querySelector('[http-equiv="refresh"]');
  var xhr = new XMLHttpRequest();
//  var uri = location.pathname.substring(1);
  var uri = location.pathname;
  var xhrContainer = document.getElementById("xhr");
  let isDownTimer;

  var advancedGeneral = document.getElementById("sb_advancedgeneral");
  var badges = document.querySelectorAll("h3 a .badge");
  var bandwidth = document.getElementById("sb_bandwidth");
  var clock = document.getElementById("clock");
  var down = document.getElementById("down");
  var general = document.getElementById("sb_general");
  var graphStats = document.getElementById("sb_graphstats");
  var internals = document.getElementById("sb_internals");
  var localTunnels = document.getElementById("sb_localtunnels");
  var memBar = document.getElementById("sb_memoryBar");
  var minigraph = document.getElementById("minigraph");
  var netStatus = document.getElementById("sb_status");
  var notice = document.getElementById("sb_notice");
  var peers = document.getElementById("sb_peers");
  var queue = document.getElementById("sb_queue");
  var routerControl = document.getElementById("sb_routerControl");
  var services = document.getElementById("sb_services");
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

  //xhr.open("GET", "/xhr1.jsp?requestURI=" + uri + "&t=" + new Date().getTime(), true);
  xhr.open("GET", "/xhr1.jsp?requestURI=" + uri, true);
  xhr.responseType = "document";
  xhr.overrideMimeType("text/html");
  xhr.setRequestHeader("Accept", "text/html");
  xhr.setRequestHeader("Cache-Control", "no-store, max-age=60");
  xhr.setRequestHeader("Content-Security-Policy",
    "default-src 'self'; style-src 'none'; script-src 'self'; frame-ancestors 'none'; object-src 'none'; media-src 'none'; base-uri 'self'"
  );

  xhr.onreadystatechange = function () {
    if (xhr.readyState === 4) {
      if (xhr.status === 200) {

        var advancedGeneralResponse = xhr.responseXML.getElementById("sb_advancedgeneral");
        var bandwidthResponse = xhr.responseXML.getElementById("sb_bandwidth");
        var clockResponse = xhr.responseXML.getElementById("clock");
        var generalResponse = xhr.responseXML.getElementById("sb_general");
        var graphStatsResponse = xhr.responseXML.getElementById("sb_graphstats");
        var internalsResponse = xhr.responseXML.getElementById("sb_internals");
        var localTunnelsResponse = xhr.responseXML.getElementById("sb_localtunnels");
        var memBarResponse = xhr.responseXML.getElementById("sb_memoryBar");
        var netStatusResponse = xhr.responseXML.querySelector("sb_netstatus");
        var noticeResponse = xhr.responseXML.getElementById("sb_notice");
        var peersResponse = xhr.responseXML.getElementById("sb_peers");
        var queueResponse = xhr.responseXML.getElementById("sb_queue");
        var routerControlResponse = xhr.responseXML.getElementById("sb_routerControl");
        var servicesResponse = xhr.responseXML.getElementById("sb_services");
        var shortGeneralResponse = xhr.responseXML.getElementById("sb_shortgeneral");
        var shutdownStatusResponse = xhr.responseXML.getElementById("sb_shutdownStatus");
        var tunnelBuildStatusResponse = xhr.responseXML.getElementById("sb_tunnelstatus");
        var tunnelsResponse = xhr.responseXML.getElementById("sb_tunnels");
        var updateBarResponse = xhr.responseXML.querySelector(".sb_updatestatus + .percentBarOuter");
        var updateFormResponse = xhr.responseXML.getElementById("sb_updateform");
        var updateProgressResponse = xhr.responseXML.getElementById("sb_updateprogress");
        var updateSectionResponse = xhr.responseXML.getElementById("sb_updatesection");
        var updateStatusResponse = xhr.responseXML.getElementById("sb_updatestatus");

        if (isDownTimer !== null) {
          clearTimeout(isDownTimer);
        }

        if (down) {
          uncollapse();
          refreshAll();
          countTunnels();
          countNewsItems();
        }

        function updateVolatile() {
          //uncollapse();
          sectionToggler();

          var b;
          for (b = 0; b < badges.length; b += 1) {
            if (badges[b] !== null) {
              var badgesResponse = xhr.responseXML.querySelectorAll("h3 a .badge");
              if (badgesResponse[b] !== null) {
                badges[b].innerHTML = badgesResponse[b].innerHTML;
              }
            }
          }

          countTunnels();
          countNewsItems();

          if (clock !== null && clockResponse !== null) {
            clock.innerHTML = clockResponse.innerHTML;
          }
          if (bandwidth !== null && bandwidthResponse !== null && !Object.is(bandwidth.innerHTML, bandwidthResponse.innerHTML)) {
            bandwidth.innerHTML = bandwidthResponse.innerHTML;
          }
          if (graphStats !== null) {
            if (bandwidth !== null && bandwidth.hidden !== true) {
              graphStats.style.opacity = null;
            } else {
                graphStats.style.opacity = "1";
            }
          }
          if (graphStats !== null && graphStatsResponse !== null) {
              graphStats.innerHTML = graphStatsResponse.innerHTML;
          }
          if (advancedGeneral !== null && advancedGeneralResponse !== null && !Object.is(advancedGeneral.innerHTML, advancedGeneralResponse.innerHTML)) {
            advancedGeneral.innerHTML = advancedGeneralResponse.innerHTML;
          }
          if (shortGeneral !== null && shortGeneralResponse !== null && !Object.is(shortGeneral.innerHTML, shortGeneralResponse.innerHTML)) {
            shortGeneral.innerHTML = shortGeneralResponse.innerHTML;
          }
          if (general !== null && generalResponse !== null && !Object.is(general.innerHTML, generalResponse.innerHTML)) {
            general.innerHTML = generalResponse.innerHTML;
          }
          if (tunnels !== null && tunnelsResponse !== null && !Object.is(tunnels.innerHTML, tunnelsResponse.innerHTML)) {
            tunnels.innerHTML = tunnelsResponse.innerHTML;
          }
          if (localTunnels !== null && localTunnelsResponse !== null && localTunnels.hidden !== true) {
            if (!Object.is(localTunnels.innerHTML, localTunnelsResponse.innerHTML)) {
              localTunnels.innerHTML = localTunnelsResponse.innerHTML;
            }
          }
          if (tunnelCount) {
            var doubleCount = document.querySelector("#tunnelCount + #tunnelCount");
            if (doubleCount) {
              doubleCount.remove();
            }
          }
          if (peers !== null && peersResponse !== null && !Object.is(peers.innerHTML, peersResponse.innerHTML)) {
            peers.innerHTML = peersResponse.innerHTML;
          }
          if (queue !== null) {
            if (queueResponse !== null && !Object.is(queue.innerHTML, queueResponse.innerHTML)) {
              queue.innerHTML = queueResponse.innerHTML;
            }
          }
          if (memBar !== null && memBarResponse !== null && !Object.is(memBar.innerHTML, memBarResponse.innerHTML)) {
            memBar.innerHTML = memBarResponse.innerHTML;
          }
          if (updateBar !== null) {
            if (updateBarResponse !== null) {
              if (!Object.is(updateBar.innerHTML, updateBarResponse.innerHTML)) {
                updateBar.innerHTML = updateBarResponse.innerHTML;
                if (updateH3 !== null) {
                  var updateH3 = document.querySelector("#sb_updatesection > h3 a").innerHTML;
                  updateH3.classList.add("updating");
                  var spinner = "<span id=\"updateSpinner\"></span>";
                  if (updateH3.innerHTML.indexOf(spinner) === -1) {
                    updateH3.innerHTML += spinner;
                  }
                }
              }
            } else {
              refreshAll();
            }
          }
          if (updateSection !== null && updateSection.classList.contains("collapsed") !== true && updateStatus !== null) {
            if (updateStatusResponse !== null && !updateBar === null) {
              if (!Object.is(updateStatus.innerHTML, updateStatusResponse.innerHTML)) {
                updateStatus.innerHTML = updateStatusResponse.innerHTML;
              }
            }
          }
          if (updateSection !== null &&  updateSection.classList.contains("collapsed") !== true && updateForm !== null) {
            if (updateFormResponse !== null) {
              if (!Object.is(updateForm.innerHTML, updateFormResponse.innerHTML)) {
                updateForm.innerHTML = updateFormResponse.innerHTML;
                if (updateSectionHR.hidden === true) {
                updateSectionHR.hidden = null;
                }
              }
            }
          }
          if (updateSection !== null && updateSection.classList.contains("collapsed") !== true && updateProgress !== null) {
            if (updateProgressResponse !== null) {
              if (!Object.is(updateProgress.innerHTML, updateProgressResponse.innerHTML)) {
                updateProgress.innerHTML = updateProgressResponse.innerHTML;
              }
            }
          }
//          if (updateSection !== null) {
//            if (updateProgressResponse !== null) {
//              if (!Object.is(updateSection.innerHTML, updateSectionResponse.innerHTML)) {
//                updateProgress.innerHTML = updateProgressResponse.innerHTML;
//              }
//            }
//          }
          if (tunnelBuildStatus !== null && tunnelBuildStatusResponse !== null && !Object.is(tunnelBuildStatus.outerHTML, tunnelBuildStatusResponse.outerHTML)) {
            tunnelBuildStatus.innerHTML = tunnelBuildStatusResponse.innerHTML;
          }
          if (notice !== null && noticeResponse !== null && !Object.is(notice.innerHTML, noticeResponse.innerHTML)) {
            notice.innerHTML = noticeResponse.innerHTML;
          }
          if (shutdownStatus !== null && shutdownStatusResponse !== null && !Object.is(shutdownStatus.innerHTML, shutdownStatusResponse.innerHTML)) {
            shutdownStatus.innerHTML = shutdownStatusResponse.innerHTML;
          }
          if (routerControl !== null && routerControlResponse !== null && !Object.is(routerControl.innerHTML, routerControlResponse.innerHTML)) {
            routerControl.outerHTML = routerControlResponse.outerHTML;
          }
          if (internals !== null && internalsResponse !== null && !Object.is(internals.innerHTML, internalsResponse.innerHTML)) {
            internals.outerHTML = internalsResponse.outerHTML;
          }
          if (services !== null && servicesResponse !== null && !Object.is(services.innerHTML, servicesResponse.innerHTML)) {
            services.outerHTML = servicesResponse.outerHTML;
          }

        }

        function checkSections() {
          var updating = document.querySelectorAll(".volatile");
          var updatingResponse = xhr.responseXML.querySelectorAll(".volatile");
          var i;
          for (i = 0; i < updating.length; i += 1) {
            if (updating[i] !== null) {
              if (updatingResponse[i] !== null) {
                updateVolatile();
                sectionToggler();
                countTunnels();
                countNewsItems();
              }
              if (localTunnels !== null && localTunnels.hidden !== true) {
                if (localTunnels.hidden !== true && updating.length !== updatingResponse.length) {
                  refreshAll();
                }
              }
            }
          }
        }

        function refreshAll() {
          if (sb !== null) {
            var sbResponse = xhr.responseXML.getElementById("sb");
            if (sbResponse !== null && !Object.is(sb.innerHTML, sbResponse.innerHTML)) {
              xhrContainer.innerHTML = sbResponse.innerHTML;
              sectionToggler();
              countTunnels();
              countNewsItems();
            }
          }
        }

        function refreshGraph() {
          var minigraph = document.getElementById("minigraph");
          var graphContainer = document.getElementById("sb_graphcontainer");
          var graphContainerHR = document.querySelector("#sb_graphcontainer + hr");
          if (minigraph) {
            if (graphContainer.hidden === true) {
              graphContainer.hidden = null;
              graphContainerHR.hidden = null;
            }
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
          var sectionTitle = document.querySelectorAll("#sidebar h3, #sidebar a");
          var a;
          for (a = 1; a < sectionTitle.length - 1; a += 1) {
            var styleInline = sectionTitle[a].getAttribute("style");
            if (styleInline) {
              sectionTitle[a].removeAttribute("style");
            }
          }

          var collapsed = document.querySelectorAll("#sidebar .volatile.collapse, #sidebar .volatile.collapse + hr, #sidebar table.volatile + hr");
          var c;
          for (c = 0; c < collapsed.length; c += 1) {
            var styleHidden = collapsed[c].getAttribute("hidden");
            if (styleHidden) {
              collapsed[c].hidden = null;
            }
          }
          sectionToggler();
          countTunnels();
          countNewsItems();
        }

        function removeMeta() {
          if (meta !== null) {
            meta.remove();
          }
        }

        if (document.hidden !== true) {
          checkSections();
          if (meta !== null) {
            removeMeta();
          }

          if (minigraph) {
            //window.requestAnimationFrame(refreshGraph);
            refreshGraph();
            var minigraphResponse = xhr.responseXML.getElementById("minigraph");
            minigraph = minigraphResponse;
          }
        }
/*
        } else if (xhr.readyState === 4 && xhr.status === 200) {

          setTimeout(function() {
            if (meta !== null) {
              removeMeta();
              refreshAll();
            }
            var metarefresh = document.createElement("meta");
            metarefresh.httpEquiv = "refresh";
            metarefresh.content = "1800";
            document.head.appendChild(metarefresh);
          }, 120000);
        }
*/
      } else {

        function isDown() {
          function hideSections() {
            var collapse = document.querySelectorAll("#sidebar .collapse");
            var h;
            for (h = 0; h < collapse.length; h += 1) {
              collapse[h].setAttribute("hidden", "");
              if (collapse[h].nextElementSibling !== null && collapse[h].nextElementSibling.nodeName === "HR") {
                collapse[h].nextElementSibling.setAttribute("hidden", "");
              }
            }
            var collapsed = document.querySelectorAll("#sidebar table.collapsed, #sb_newsheadings.collapsed");
            var c;
            for (c = 0; c < collapsed.length; c += 1) {
              collapsed[c].classList.remove("collapsed");
              if (collapsed[c].nextElementSibling !== null && collapsed[c].nextElementSibling.nodeName === "HR") {
                collapsed[c].nextElementSibling.setAttribute("hidden", "");
              }
            }
            if (shutdownStatus !== null) {
              shutdownStatus.setAttribute("hidden", "");
            }
            if (localTunnels) {
              localTunnels.innerHTML = '<tr id="routerdown"><td colspan="3"></td></tr>';
              if (tunnelCount !== null) {
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
            if (clock !== null) {
              clock.innerHTML = "--:--:--";
            }
            var badges = document.querySelectorAll("h3 a .badge");
            var b;
            for (b = 0; b < badges.length; b += 1) {
              if (badges[b] !== null) {
                badges[b].innerHTML = "-";
              }
            }
            netStatus.innerHTML = '<span id="down">Router is down</span>';
          }
          hideSections();
          modElements();
        }
        var isDownTimer = setTimeout(isDown, 5000);
      }
    }
  };

  xhr.addEventListener("loaded", () => {
    sectionToggler();
    countTunnels();
    countNewsItems();
  });
  xhr.send();

}

export {refreshSidebar};