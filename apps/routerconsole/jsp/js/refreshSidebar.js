function refreshSidebar(timestamp) {

  var xhr = new XMLHttpRequest();
  var uri = location.pathname.substring(1);
  var xhrContainer = document.getElementById("xhr");

  var sb = document.getElementById("sb");
  var down = document.getElementById("down");
  var services = document.getElementById("sb_services");
  var advanced = document.getElementById("sb_advanced");
  var internals = document.getElementById("sb_internals");
  var localtunnels = document.getElementById("sb_localtunnels");

  xhr.open('GET', '/xhr1.jsp?requestURI=' + uri + '&t=' + new Date().getTime(), true);
  xhr.responseType = "document";
  xhr.overrideMimeType('text/html');
  xhr.setRequestHeader('Accept', 'text/html');
  xhr.setRequestHeader('Cache-Control', 'no-store');
  xhr.setRequestHeader("Content-Security-Policy", "default-src 'self'; style-src 'none'; script-src 'self'; frame-ancestors 'none'; object-src 'none'; media-src 'none'");
  xhr.onreadystatechange = function() {
    if (xhr.readyState == 4) {
      if (xhr.status == 200) {

        if (xhrContainer && down) {
          var sbResponse = xhr.responseXML.getElementById("sb");
          xhrContainer.innerHTML = sbResponse.innerHTML;
        }

        var general = document.getElementById("sb_general");
        var shortgeneral = document.getElementById("sb_shortgeneral");
        var advgeneral = document.getElementById("sb_advancedgeneral");
        var netstatus = document.getElementById("sb_status");
        var updatestatus = document.getElementById("sb_updatestatus");
        var peers = document.getElementById("sb_peers");
        var advpeers = document.getElementById("sb_peersadvanced");
        var bandwidth = document.getElementById("sb_bandwidth");
        var ram = document.getElementById("sb_memoryBar");
        var clock = document.getElementById("clock");
        var tunnels = document.getElementById("sb_tunnels");
        var queue = document.getElementById("sb_queue");
        var tunnelstatus = document.getElementById("sb_tunnelstatus");
        var control = document.getElementById("sb_routerControl");
        var shutdownstatus = document.getElementById("sb_shutdownStatus");
        var graphstats = document.getElementById("sb_graphstats");
        var minigraph = document.getElementById("minigraph");
        var notify = document.getElementById("sb_notice");

        var servicesResponse = xhr.responseXML.getElementById("sb_services");
        var generalResponse = xhr.responseXML.getElementById("sb_general");
        var shortgeneralResponse = xhr.responseXML.getElementById("sb_shortgeneral");
        var advgeneralResponse = xhr.responseXML.getElementById("sb_advancedgeneral");
        var netstatusResponse = xhr.responseXML.getElementById("sb_status");
        var updatestatusResponse = xhr.responseXML.getElementById("sb_updatestatus");
        var peersResponse = xhr.responseXML.getElementById("sb_peers");
        var advpeersResponse = xhr.responseXML.getElementById("sb_peersadvanced");
        var bandwidthResponse = xhr.responseXML.getElementById("sb_bandwidth");
        var ramResponse = xhr.responseXML.getElementById("sb_memoryBar");
        var clockResponse = xhr.responseXML.getElementById("clock");
        var tunnelsResponse = xhr.responseXML.getElementById("sb_tunnels");
        var queueResponse = xhr.responseXML.getElementById("sb_queue");
        var tunnelstatusResponse = xhr.responseXML.getElementById("sb_tunnelstatus");
        var localtunnelsResponse = xhr.responseXML.getElementById("sb_localtunnels");
        var controlResponse = xhr.responseXML.getElementById("sb_routerControl");
        var shutdownstatusResponse = xhr.responseXML.getElementById("sb_shutdownStatus");
        var graphstatsResponse = xhr.responseXML.getElementById("sb_graphstats");
        var minigraphResponse = xhr.responseXML.getElementById("minigraph");
        var notifyResponse = xhr.responseXML.getElementById("sb_notice");

        if (services) {
          var servicesParent = services.parentNode;
          if (!Object.is(services.innerHTML, servicesResponse.innerHTML))
            servicesParent.replaceChild(servicesResponse, services);
        }
        if (general) {
          var generalParent = general.parentNode;
          if (!Object.is(general.innerHTML, generalResponse.innerHTML))
            generalParent.replaceChild(generalResponse, general);
        }
        if (shortgeneral) {
          var shortgeneralParent = shortgeneral.parentNode;
          if (!Object.is(shortgeneral.innerHTML, shortgeneralResponse.innerHTML))
            shortgeneralParent.replaceChild(shortgeneralResponse, shortgeneral);
        }
        if (advgeneral) {
          var advgeneralParent = advgeneral.parentNode;
          if (!Object.is(advgeneral.innerHTML, advgeneralResponse.innerHTML))
            advgeneralParent.replaceChild(advgeneralResponse, advgeneral);
        }
        if (netstatus) {
          var netstatusParent = netstatus.parentNode;
          if (!Object.is(netstatus.innerHTML, netstatusResponse.innerHTML))
            netstatusParent.replaceChild(netstatusResponse, netstatus);
        }
        if (updatestatus) {
          var updatestatusParent = updatestatus.parentNode;
          if (!Object.is(updatestatus.innerHTML, updatestatusResponse.innerHTML))
            updatestatusParent.replaceChild(updatestatusResponse, updatestatus);
        }
        if (notify) {
          var notifyParent = notify.parentNode;
          if (!Object.is(notify.innerHTML, notifyResponse.innerHTML))
            notifyParent.replaceChild(notifyResponse, notify);
        }
        if (peers) {
          var peersParent = peers.parentNode;
          if (!Object.is(peers.innerHTML, peersResponse.innerHTML))
            peersParent.replaceChild(peersResponse, peers);
        }
        if (advpeers) {
          var advpeersParent = advpeers.parentNode;
          if (!Object.is(advpeers.innerHTML, advpeersResponse.innerHTML))
            advpeersParent.replaceChild(advpeersResponse, advpeers);
        }
        if (bandwidth) {
          var bandwidthParent = bandwidth.parentNode;
          if (!Object.is(bandwidth.innerHTML, bandwidthResponse.innerHTML))
            bandwidthParent.replaceChild(bandwidthResponse, bandwidth);
        }
        if (minigraph && minigraphResponse) {
            var graphstatsParent = graphstats.parentNode;
              graphstatsParent.replaceChild(graphstatsResponse, graphstats);
        }
        if (ram) {
          var ramParent = ram.parentNode;
          if (!Object.is(ram.innerHTML, ramResponse.innerHTML))
            bandwidthParent.replaceChild(ramResponse, ram);
        }
        if (clock) {
          var clockParent = clock.parentNode;
          if (!Object.is(clock.innerHTML, clockResponse.innerHTML))
            clockParent.replaceChild(clockResponse, clock);
        }
        if (tunnels) {
          var tunnelsParent = tunnels.parentNode;
          if (!Object.is(tunnels.innerHTML, tunnelsResponse.innerHTML))
            tunnelsParent.replaceChild(tunnelsResponse, tunnels);
        }
        if (queue) {
          var queueParent = queue.parentNode;
          if (!Object.is(queue.innerHTML, queueResponse.innerHTML))
            queueParent.replaceChild(queueResponse, queue);
        }
        if (tunnelstatus) {
          var tunnelstatusParent = tunnelstatus.parentNode;
          if (!Object.is(tunnelstatus.innerHTML, tunnelstatusResponse.innerHTML))
            tunnelstatusParent.replaceChild(tunnelstatusResponse, tunnelstatus);
        }
        if (shutdownstatus) {
          var shutdownstatusParent = shutdownstatus.parentNode;
          if (!Object.is(shutdownstatus.innerHTML, shutdownstatusResponse.innerHTML))
            shutdownstatusParent.replaceChild(shutdownstatusResponse, shutdownstatus);
        }
        if (localtunnels) {
          var localtunnelsParent = localtunnels.parentNode;
          if (!Object.is(localtunnels.innerHTML, localtunnelsResponse.innerHTML))
            localtunnelsParent.replaceChild(localtunnelsResponse, localtunnels);
        }

        if (minigraph) {
          window.requestAnimationFrame(refreshGraph);
          var minigraphResponse = xhr.responseXML.getElementById("minigraph");
          minigraph = minigraphResponse;
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
        var sidebar = document.getElementById("sidebar");
        var digits = sidebar.getElementsByClassName("digits");
        var i;
        for (i = 0; i < digits.length; i++) {
          digits[i].innerHTML = "---&nbsp;";
        }

        if (services) {
          services.nextElementSibling.remove();
          services.remove();
        }
        if (advanced) {
          advanced.nextElementSibling.remove();
          advanced.remove();
        }
        if (internals) {
          internals.nextElementSibling.remove();
          internals.remove();
        }
        if (localtunnels)
          localtunnels.remove();

        setTimeout(function() {
          var failMessage = "<hr><b><span id=\"down\">Router is down<\/span><\/b>";
          xhrContainer.innerHTML = failMessage;
        }, 3000);

      }
    }
  }
  xhr.send();
}

export {refreshSidebar};