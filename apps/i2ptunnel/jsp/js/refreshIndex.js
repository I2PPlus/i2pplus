import {initToggleInfo} from "./toggleTunnelInfo.js";
var control = document.getElementById("globalTunnelControl");
var toggle = document.getElementById("toggleInfo");

function refreshTunnelStatus(timestamp) {
  var index = document.getElementById("page");

  var url = window.location.pathname;

  var xhrtunnman = new XMLHttpRequest();

  xhrtunnman.open('GET', url + "?t=" + new Date().getTime(), true);
  xhrtunnman.setRequestHeader("If-Modified-Since", "Sat, 1 Jan 2000 00:00:00 GMT");
  xhrtunnman.responseType = "document";
  xhrtunnman.onreadystatechange = function() {

    if (xhrtunnman.readyState == 4) {
      if (xhrtunnman.status == 200) {

        var uninit = document.getElementById("notReady");
        var down = document.getElementById("down");
        if (uninit || down) {
          var uninitResponse = xhrtunnman.responseXML.getElementById("notReady");
          var downResponse = xhrtunnman.responseXML.getElementById("notReady");
          if (uninitResponse || downResponse)
            window.requestAnimationFrame(refreshAll);
          else
            reloadPage();
        } else {
          window.requestAnimationFrame(updateVolatile);
        }

        function updateVolatile(timestamp) {
          var updating = document.getElementsByClassName("volatile");
          var updatingResponse = xhrtunnman.responseXML.getElementsByClassName("volatile");
          var i;
          for (i = 0; i < updating.length; i++) {
            if (!Object.is(updating[i].innerHTML, updatingResponse[i].innerHTML))
              updating[i].innerHTML = updatingResponse[i].innerHTML;
            else if (updating.length != updatingResponse.length)
              window.requestAnimationFrame(refreshPanels);
          }
        }

        function refreshPanels(timestamp) {
          var screenlog = document.getElementById("messages");
          var screenlogResponse = xhrtunnman.responseXML.getElementById("messages");
          var servers = document.getElementById("servers");
          var serversResponse = xhrtunnman.responseXML.getElementById("servers");
          var clients = document.getElementById("clients");
          var clientsResponse = xhrtunnman.responseXML.getElementById("clients");
          if (!Object.is(screenlog.innerHTML, screenlogResponse.innerHTML))
            screenlog.innerHTML = screenlogResponse.innerHTML;
          if (!Object.is(servers.innerHTML, serversResponse.innerHTML))
            servers.innerHTML = serversResponse.innerHTML;
          if (!Object.is(clients.innerHTML, clientsResponse.innerHTML))
            clients.innerHTML = clientsResponse.innerHTML;
        }

        function refreshAll(timestamp) {
          var indexResponse = xhrtunnman.responseXML.getElementById("page");
          if (typeof indexResponse != "undefined" && !Object.is(index.innerHTML, indexResponse.innerHTML))
            index.innerHTML = indexResponse.innerHTML;
        }

        function reloadPage() {
          location.reload(true);
        }

      } else {
        function noRouter() {
          var down = "<div id=\"down\"><b><span>Router is down<\/span><\/b><\/div>";
          index.innerHTML = down;
        }
        setTimeout(noRouter, 6000)
      }
    }
  }
  xhrtunnman.send();
}

function bindToggle() {
  if (toggle)
    toggle.addEventListener("click", initToggleInfo, false);
}

function refreshIndex() {
  window.requestAnimationFrame(refreshTunnelStatus);
  bindToggle();
}

bindToggle();
setInterval(refreshIndex, 5000);
