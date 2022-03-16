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

  xhr.open("GET", "/xhr1.jsp?requestURI=" + uri + "&t=" + new Date().getTime(), true);
  xhr.responseType = "document";
  xhr.overrideMimeType("text/html");
  xhr.setRequestHeader("Accept", "text/html");
  xhr.setRequestHeader("Cache-Control", "no-store");
  xhr.setRequestHeader("Content-Security-Policy", "default-src 'self'; style-src 'none'; script-src 'self'; frame-ancestors 'none'; object-src 'none'; media-src 'none'; base-uri 'self'");
  xhr.onreadystatechange = function () {
    if (xhr.readyState == 4) {
      if (xhr.status == 200) {
        if (down) {
          var sbResponse = xhr.responseXML.getElementById("sb");
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
            var updating = document.getElementsByClassName("volatile");
            var updatingResponse = xhr.responseXML.getElementsByClassName("volatile");
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
          }

          function refreshAll(timestamp) {
            var sbResponse = xhr.responseXML.sb;
            if (typeof sbResponse !== "undefined" && !Object.is(sb.innerHTML, sbResponse.innerHTML)) sb.innerHTML = sbResponse.innerHTML;
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
            redirect.content = "60";
            document.head.appendChild(redirect);
          }, 60000);
        }
      } else {
        function isDown() {
          var sbdown = document.getElementById("sidebar");
          var digits = sbdown.getElementsByClassName("digits");
          var i;
          for (i = 0; i < digits.length; i++) {
            digits[i].innerHTML = "---&nbsp;";
          }

          netstatus.innerHTML = '<span id="down">Router is down</span>';

          if (services) {
            if (services.nextElementSibling !== "undefined" && services.nextElementSibling != null) services.nextElementSibling.remove();
            services.remove();
          }
          if (advanced) {
            if (advanced.nextElementSibling !== "undefined" && advanced.nextElementSibling != null) advanced.nextElementSibling.remove();
            advanced.remove();
          }
          if (internals) {
            if (internals.nextElementSibling !== "undefined" && internals.nextElementSibling != null) internals.nextElementSibling.remove();
            internals.remove();
          }
          if (graph) {
            if (typeof graph.nextElementSibling !== "undefined" || graph.nextElementSibling != null) graph.nextElementSibling.remove();
            graph.remove();
          }
          if (tunnelstatus) {
            if (typeof tunnelstatus.nextElementSibling !== "undefined" || tunnelstatus.nextElementSibling !== null) tunnelstatus.nextElementSibling.remove();
            tunnelstatus.remove();
          }
          if (shutdownstatus) {
            if (typeof shutdownstatus.nextElementSibling !== "undefined" || shutdownstatus.nextElementSibling != null) shutdownstatus.nextElementSibling.remove();
            shutdownstatus.remove();
          }
          if (localtunnels) localtunnels.innerHTML = '<tr><td colspan="3">&nbsp;</td></tr>';
          if (updatesection) updatesection.remove();
        }

        setTimeout(isDown, 10000);
      }
    }
  };
  xhr.send();
}

export { refreshSidebar };
