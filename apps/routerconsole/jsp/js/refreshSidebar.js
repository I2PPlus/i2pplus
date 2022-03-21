/* RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

function refreshSidebar(timestamp) {
  'use strict';
  var pageVisibility = document.visibilityState;
  var xhr = new XMLHttpRequest();
  var uri = location.pathname.substring(1);
  var xhrContainer = document.getElementById("xhr");

  var down = document.getElementById("down");
  var localtunnels = document.getElementById("sb_localtunnels");
  var netstatus = document.getElementById("sb_status");
  var sb = document.querySelector("#sidebar");
  var services = document.getElementById("sb_services");
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
        var sbResponse = xhr.responseXML.getElementById("sb");
        if (down) {
          window.requestAnimationFrame(removeStyles);
          window.requestAnimationFrame(refreshAll);
        }

        function updateVolatile(timestamp) {
          removeStyles();
          var updating = document.querySelectorAll(".volatile:not(.hide");
          var updatingResponse = xhr.responseXML.querySelectorAll(".volatile:not(.hide)");
          var i;
          for (i = 0; i < updating.length; i += 1) {
            if (typeof updating[i] !== "undefined" && typeof updatingResponse[i] !== "undefined") {
              if (!Object.is(updating[i].innerHTML, updatingResponse[i].innerHTML)) {
                if (updating.length === updatingResponse.length) {
                  updating[i].outerHTML = updatingResponse[i].outerHTML;
                } else {
                  window.requestAnimationFrame(refreshAll);
                }
              }
            }
          }
        }

        function refreshAll(timestamp) {
          if (typeof sbResponse !== "undefined" && !Object.is(sb.innerHTML, sbResponse.innerHTML)) {
            xhrContainer.innerHTML = sbResponse.innerHTML;
          } else {
            location.reload(true);
          }
        }

        function refreshGraph(timestamp) {
          var minigraph = document.getElementById("minigraph");
          if (minigraph) {
            const ctx = minigraph.getContext("2d");
            const image = new Image(245, 50);
            image.onload = renderGraph;
            image.src = "/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=" + new Date().getTime();
            ctx.globalCompositeOperation = "source-out";
            ctx.globalAlpha = 1;

            function renderGraph() {
              minigraph.width = 245;
              minigraph.height = 50;
              ctx.drawImage(this, 0, 0, 245, 50);
            }
          }
        }

        function removeStyles(timestamp) {
          var links = document.querySelectorAll("#sidebar h3, #sidebar a");
          var a;
          for (a = 1; a < links.length - 1; a += 1) {
            var style = links[a].getAttribute("style");
            if (links[a].style) {
              links[a].removeAttribute("style");
            }
          }
          var sep = document.querySelector("#sb_tunnelstatus + hr");
          if (sep != null && sep.hasAttribute("hidden")) {
            sep.removeAttribute("hidden");
          }
        }

        function removeMeta() {
          var meta = document.querySelector("[http-equiv='meta']");
          if (meta != null) {
            meta.remove;
          }
        }

        if (pageVisibility == "visible") {
          removeMeta();
          window.requestAnimationFrame(updateVolatile);

          var minigraph = document.getElementById("minigraph");
          if (minigraph) {
            window.requestAnimationFrame(refreshGraph);
            var minigraphResponse = xhr.responseXML.getElementById("minigraph");
            minigraph = minigraphResponse;
          }

        } else {

          setTimeout(function () {
            var metarefresh = document.createElement("meta");
            metarefresh.httpEquiv = "refresh";
            metarefresh.content = "75";
            document.head.appendChild(metarefresh);
          }, 90000);
        }

      } else {

        function isDown() {
          function hideSections(timestamp) {
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

          function modElements(timestamp) {
            var links = document.querySelectorAll("#sidebar h3, #sidebar a");
            var a;
            for (a = 1; a < links.length - 1; a += 1) {
              links[a].setAttribute("style", "pointer-events: none");
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
            netstatus.innerHTML = '<span id="down">Router is down</span>';
          }
          window.requestAnimationFrame(hideSections);
          window.requestAnimationFrame(modElements);
        }
        setTimeout(isDown, 10000);
      }
    }
  };
  xhr.send();
}

export {refreshSidebar};
