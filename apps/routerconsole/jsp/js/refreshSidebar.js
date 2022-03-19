/* RefreshSidebar by dr|z3d */
/* License: AGPLv3 or later */

function refreshSidebar(timestamp) {
  var pageVisibility = document.visibilityState;
  var xhr = new XMLHttpRequest();
  var uri = location.pathname.substring(1);
  var xhrContainer = document.getElementById("xhr");

  var down = document.getElementById("down");
  var sb = document.getElementById("sb");
  var services = document.getElementById("sb_services");
  var advanced = document.getElementById("sb_advanced");
  var internals = document.getElementById("sb_internals");
  var localtunnels = document.getElementById("sb_localtunnels");
  var netstatus = document.getElementById("sb_status");
  var tunnelstatus = document.getElementById("sb_tunnelstatus");
  var shutdownstatus = document.getElementById("sb_shutdownStatus");
  var updatesection = document.getElementById("sb_updatesection");
  var graph = document.getElementById("sb_graphcontainer");
  var control = document.getElementById("sb_routerControl");

  xhr.open("GET", "/xhr1.jsp?requestURI=" + uri + "&t=" + new Date().getTime(), true);
  xhr.responseType = "document";
  xhr.overrideMimeType("text/html");
  xhr.setRequestHeader("Accept", "text/html");
  xhr.setRequestHeader("Cache-Control", "no-store, max-age=60");
  xhr.setRequestHeader("Content-Security-Policy", "default-src 'self'; style-src 'none'; script-src 'self'; frame-ancestors 'none'; object-src 'none'; media-src 'none'; base-uri 'self'");
  xhr.onreadystatechange = function () {
    if (xhr.readyState == 4) {
      if (xhr.status == 200) {
        var sbResponse = xhr.responseXML.getElementById("sb");
        if (down) {
          xhrContainer.innerHTML = sbResponse.innerHTML;
        }

        if (pageVisibility == "visible") {
          if (document.querySelector("[http-equiv='meta']") != null) {
            document.querySelector("[http-equiv='meta']").remove;
          }
          window.requestAnimationFrame(updateVolatile);

          if (minigraph) {
            window.requestAnimationFrame(refreshGraph);
            var minigraphResponse = xhr.responseXML.getElementById("minigraph");
            minigraph = minigraphResponse;
          }

          function updateVolatile(timestamp) {
            var updating = document.querySelectorAll(".volatile:not(.hide");
            var updatingResponse = xhr.responseXML.querySelectorAll(".volatile:not(.hide)");
            var i;
            for (i = 0; i < updating.length; i++) {
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
            var links = document.querySelectorAll("h3, a");
            var a;
            for (a = 1; a < links.length -1; a++) {
              links[a].removeAttribute("style", "");
            }
          }

          function refreshAll(timestamp) {
            var sbResponse = xhr.responseXML.getElementById("sb");
            if (typeof sbResponse !== "undefined" && !Object.is(sb.innerHTML, sbResponse.innerHTML))
              xhrContainer.innerHTML = sbResponse.innerHTML;
          }

          function refreshGraph(timestamp) {
            var minigraph = document.getElementById("minigraph");
            var routerdown = document.getElementById("down");
            if (minigraph) {
              const ctx = minigraph.getContext("2d");
              const image = new Image(245, 50);
              image.onload = renderGraph;
              image.src = "/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=" + new Date().getTime();
              ctx.globalCompositeOperation = "copy";
              ctx.globalAlpha = 1;

              function renderGraph() {
                minigraph.width = 245;
                minigraph.height = 50;
                ctx.drawImage(this, 0, 0, 245, 50);
              }
            }
          }
        } else {
          setTimeout(function() {
            var redirect = document.createElement("meta");
            redirect.httpEquiv = "refresh";
            redirect.content = "75";
            document.head.appendChild(redirect);
          }, 90000);
        }
      } else {
        function isDown() {
          var links = document.querySelectorAll("h3, a");
          var a;
          for (a = 1; a < links.length -1; a++) {
            links[a].setAttribute("style", "pointer-events: none");
          }

          var digits = document.querySelectorAll(".digits");
          var i;
          for (i = 0; i < digits.length; i++) {
            digits[i].innerHTML = "---&nbsp;";
          }

          var clock = document.querySelector("#clock");
          clock.innerHTML = "--:--:--";
          netstatus.innerHTML = '<span id="down">Router is down</span>';

          if (services) {
            services.setAttribute("hidden", "");
            if (services.nextElementSibling != null) {
              services.nextElementSibling.setAttribute("hidden", "");
            }
          }
          if (advanced) {
            advanced.setAttribute("hidden", "");
            if (advanced.nextElementSibling != null) {
              advanced.nextElementSibling.setAttribute("hidden", "");
            }
          }
          if (internals) {
            internals.setAttribute("hidden", "");
            if (internals.nextElementSibling != null) {
              internals.nextElementSibling.setAttribute("hidden", "");
            }
          }
          if (graph) {
            graph.setAttribute("hidden", "");
            graph.setAttribute("style", "display: none");
            if (graph.nextElementSibling != null) {
              graph.nextElementSibling.setAttribute("hidden", "");
            }
          }
          if (tunnelstatus) {
            tunnelstatus.setAttribute("hidden", "");
            if (tunnelstatus.nextElementSibling != null) {
              tunnelstatus.nextElementSibling.setAttribute("hidden", "");
            }
          }
          if (shutdownstatus) {
            shutdownstatus.setAttribute("hidden", "");
          }
          if (control) {
            control.setAttribute("hidden", "");
            if (control.nextElementSibling != null) {
              control.nextElementSibling.setAttribute("hidden", "");
            }
          }
          if (updatesection) {
            updatesection.setAttribute("hidden", "");
          }
          if (localtunnels) {
            localtunnels.innerHTML = '<tr><td colspan="3"></td></tr>';
          }
        }

        setTimeout(isDown, 10000);
      }
    }
  };
  xhr.send();
}

export { refreshSidebar };
