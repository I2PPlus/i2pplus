import {initToggleInfo} from "./toggleTunnelInfo.js";
var control = document.getElementById("globalTunnelControl");
var countClient = document.getElementById("countClient");
var countServer = document.getElementById("countServer");
var index = document.getElementById("page");
var messages = document.getElementById("tunnelMessages");
var notReady = document.getElementById("notReady");
var toggle = document.getElementById("toggleInfo");
var url = window.location.pathname;
var xhrtunnman = new XMLHttpRequest();

function refreshTunnelStatus() {
  var isDown = document.getElementById("down");
  xhrtunnman.open('GET', url, true);
  xhrtunnman.responseType = "document";
  xhrtunnman.setRequestHeader("Cache-Control", "no-cache");
  xhrtunnman.onreadystatechange = function() {
    if (xhrtunnman.readyState == 4) {
      if (xhrtunnman.status == 200) {
        if (isDown && xhrtunnman.responseXML.getElementById("globalTunnelControl") !== null) {
          reloadPage();
        } else if (notReady) {
          var notReadyResponse = xhrtunnman.responseXML.getElementById("notReady");
          if (notReadyResponse) {refreshAll();}
          else {reloadPage();}
        } else {
          updateVolatile();
        }
      } else {
        setTimeout(noRouter, 10000);
        function noRouter() {
          var down = "<div id=down class=notReady><b><span>Router is down</span></b></div>";
          index.innerHTML = down;
          if (isDown && xhrtunnman.responseXML.getElementById("globalTunnelControl") !== null) {
            reloadPage();
          }
        }
      }
    }
  }
  countServices();
  xhrtunnman.send();
}

function countServices() {
  var runningClientCount = document.querySelector("#countClient .running");
  var runningClient = document.getElementsByClassName("cli statusRunning");
  var runningServerCount = document.querySelector("#countServer .running");
  var runningServer = document.getElementsByClassName("svr statusRunning");
  var standbyClientCount = document.querySelector("#countClient .standby");
  var standbyClient = document.getElementsByClassName("cli statusStandby");
  var standbyServerCount = document.querySelector("#countServer .standby");
  var standbyServer = document.getElementsByClassName("svr statusStandby");
  var startingClientCount = document.querySelector("#countClient .starting");
  var startingClient = document.getElementsByClassName("cli statusStarting");
  var startingServerCount = document.querySelector("#countServer .starting");
  var startingServer = document.getElementsByClassName("svr statusStarting");
  var stoppedClientCount = document.querySelector("#countClient .stopped");
  var stoppedClient = document.getElementsByClassName("cli statusNotRunning");
  var stoppedServerCount = document.querySelector("#countServer .stopped");
  var stoppedServer = document.getElementsByClassName("svr statusNotRunning");
  if (control !== null) {
    if (runningClient !== null && runningClient.length > 0) {
      runningClientCount.innerHTML = " x " + runningClient.length;
    } else if (runningClient !== null && runningClientCount !== null) {
      runningClientCount.innerHTML == "";
    }
    if (runningServer !== null && runningServer.length > 0) {
      runningServerCount.innerHTML = " x " + runningServer.length;
    } else if (runningServer !== null && runningServerCount !== null) {
      runningServerCount.innerHTML = "";
    }
    if (standbyClient !== null && standbyClient.length > 0) {
      standbyClientCount.innerHTML = " x " + standbyClient.length;
    } else if (standbyClient !== null && standbyClientCount !== null) {
      standbyClientCount.innerHTML = "";
    }
    if (startingClient !== null && startingClient.length > 0) {
      startingClientCount.innerHTML = " x " + startingClient.length;
    } else if (startingClient !== null && startingClientCount !== null) {
      startingClientCount.innerHTML = "";
    }
    if (startingServer !== null && startingServer.length > 0) {
      startingServerCount.innerHTML = " x " + startingServer.length;
    } else if (startingServer !== null && startingServerCount !== null) {
      startingServerCount.innerHTML = "";
    }
    if (stoppedClient !== null && stoppedClient.length > 0) {
      stoppedClientCount.innerHTML = " x " + stoppedClient.length;
    } else if (stoppedClient !== null && stoppedClientCount !== null) {
      stoppedClientCount.innerHTML = "";
    }
    if (stoppedServer !== null && stoppedServer.length > 0) {
      stoppedServerCount.innerHTML = " x " + stoppedServer.length;
    } else if (stoppedServer !== null && stoppedServerCount !== null) {
      stoppedServerCount.innerHTML = "";
    }
  }
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
}

function refreshPanels() {
  var servers = document.getElementById("serverTunnels");
  var serversResponse = xhrtunnman.responseXML.getElementById("serverTunnels");
  var clients = document.getElementById("clientTunnels");
  var clientsResponse = xhrtunnman.responseXML.getElementById("clientTunnels");
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
document.addEventListener("DOMContentLoaded", countServices(), true);