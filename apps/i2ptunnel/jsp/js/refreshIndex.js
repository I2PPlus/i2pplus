import {initToggleInfo} from "./toggleTunnelInfo.js";
var control = document.getElementById("globalTunnelControl");
var countServer = document.getElementById("countServer");
var countClient = document.getElementById("countClient");
var messages = document.getElementById("tunnelMessages");
var toggle = document.getElementById("toggleInfo");

var runningClientCount = document.querySelector("#countClient .running");
var runningClient = document.querySelectorAll("#clientTunnels .statusRunning");
var runningServerCount = document.querySelector("#countServer .running");
var runningServer = document.querySelectorAll("#serverTunnels .statusRunning");
var standbyClientCount = document.querySelector("#countClient .standby");
var standbyClient = document.querySelectorAll("#clientTunnels .statusStandby");
var standbyServerCount = document.querySelector("#countServer .standby");
var standbyServer = document.querySelectorAll("#serverTunnels .statusStandby");
var startingClientCount = document.querySelector("#countClient .starting");
var startingClient = document.querySelectorAll("#clientTunnels .statusStarting");
var startingServerCount = document.querySelector("#countServer .starting");
var startingServer = document.querySelectorAll("#serverTunnels .statusStarting");
var stoppedClientCount = document.querySelector("#countClient .stopped");
var stoppedClient = document.querySelectorAll("#clientTunnels .statusNotRunning");
var stoppedServerCount = document.querySelector("#countServer .stopped");
var stoppedServer = document.querySelectorAll("#serverTunnels .statusNotRunning");

function refreshTunnelStatus(timestamp) {
  var index = document.getElementById("page");
  var url = window.location.pathname;
  var xhrtunnman = new XMLHttpRequest();

  xhrtunnman.open('GET', url, true);
  xhrtunnman.responseType = "document";
  xhrtunnman.setRequestHeader("Cache-Control", "no-cache");
  xhrtunnman.onreadystatechange = function() {

    if (xhrtunnman.readyState == 4) {
      if (xhrtunnman.status == 200) {

        var uninit = document.getElementById("notReady");
        var down = document.getElementById("down");
        if (uninit || down) {
          var uninitResponse = xhrtunnman.responseXML.getElementById("notReady");
          var downResponse = xhrtunnman.responseXML.getElementById("notReady");
          if (uninitResponse || downResponse) {
            refreshAll();
          } else {
            reloadPage();
          }
        } else {
          updateVolatile();
        }

        function updateVolatile() {
          var updating = document.getElementsByClassName("volatile");
          var updatingResponse = xhrtunnman.responseXML.getElementsByClassName("volatile");
          if (messages !== null) {
            var messagesResponse = xhrtunnman.responseXML.getElementById("tunnelMessages");
            if (!Object.is(messages.innerHTML, messages.innerHTML)) {
              messages.innerHTML = messagesResponse.innerHTML;
            }
          }
          var i;
          for (i = 0; i < updating.length; i++) {
            if (!Object.is(updating[i].innerHTML, updatingResponse[i].innerHTML)) {
              updating[i].innerHTML = updatingResponse[i].innerHTML;
            } else if (updating.length != updatingResponse.length) {
              refreshPanels();
            }
          }
          countServices();
        }

        function countServices() {
          if (runningClient !== null && runningClient.length > 0) {
            runningClientCount.innerHTML = " x " + runningClient.length;
            runningClientCount.hidden = false;
          } else {
            runningClientCount.hidden = true;
          }
          if (runningServer !== null && runningServer.length > 0) {
            runningServerCount.innerHTML = " x " + runningServer.length;
            runningServerCount.hidden = false;
          } else {
            runningServerCount.hidden = true;
          }
          if (standbyClient !== null && standbyClient.length > 0) {
            standbyClientCount.innerHTML = " x " + standbyClient.length;
            standbyClientCount.hidden = false;
          } else {
            standbyClientCount.hidden = true;
          }
          if (standbyServer !== null && standbyServer.length > 0) {
            standbyServerCount.innerHTML = " x " + standbyServer.length;
            standbyServerCount.hidden = false;
          } else {
            standbyServerCount.hidden = true;
          }
          if (startingClient !== null && startingClient.length > 0) {
            startingClientCount.innerHTML = " x " + startingClient.length;
            startingClientCount.hidden = false;
          } else {
            startingClientCount.hidden = true;
          }
          if (startingServer !== null && startingServer.length > 0) {
            startingServerCount.innerHTML = " x " + startingServer.length;
            startingServerCount.hidden = false;
          } else {
            startingServerCount.hidden = true;
          }
          if (stoppedClient !== null && stoppedClient.length > 0) {
            stoppedClientCount.innerHTML = " x " + stoppedClient.length;
            stoppedClientCount.hidden = false;
          } else {
            stoppedClientCount.hidden = true;
          }
          if (stoppedServer !== null && stoppedServer.length > 0) {
            stoppedServerCount.innerHTML = " x " + stoppedServer.length;
            stoppedServerCount.hidden = false;
          } else {
            stoppedServerCount.hidden = true;
          }
        }

        function refreshPanels() {
          var servers = document.getElementById("servers");
          var serversResponse = xhrtunnman.responseXML.getElementById("servers");
          var clients = document.getElementById("clients");
          var clientsResponse = xhrtunnman.responseXML.getElementById("clients");
          if (messages !== null) {
            var messagesResponse = xhrtunnman.responseXML.getElementById("tunnelMessages");
            if (!Object.is(messages.innerHTML, messagesResponse.innerHTML)) {
              messages.innerHTML = messagesResponse.innerHTML;
            }
          }
          if (!Object.is(servers.innerHTML, serversResponse.innerHTML)) {
            servers.innerHTML = serversResponse.innerHTML;
          }
          if (!Object.is(clients.innerHTML, clientsResponse.innerHTML)) {
            clients.innerHTML = clientsResponse.innerHTML;
          }
        }

        function refreshAll() {
          var indexResponse = xhrtunnman.responseXML.getElementById("page");
          if (typeof indexResponse != "undefined" && !Object.is(index.innerHTML, indexResponse.innerHTML)) {
            index.innerHTML = indexResponse.innerHTML;
          }
        }

        function reloadPage() {
          location.reload(true);
        }

      } else {
        function noRouter() {
          var down = "<div id=down><b><span>Router is down<\/span><\/b><\/div>";
          index.innerHTML = down;
        }
        setTimeout(noRouter, 10000)
      }
    }
  }
  xhrtunnman.send();
}

function bindToggle() {
  if (toggle) {
    toggle.addEventListener("click", initToggleInfo, false);
  }
}

function refreshIndex() {
  refreshTunnelStatus();
  bindToggle();
}

bindToggle();
setInterval(refreshIndex, 5000);
