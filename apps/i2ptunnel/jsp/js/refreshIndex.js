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
        if (isDown && xhrtunnman.responseXML.getElementById("globalTunnelControl")) {
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
          if (isDown && xhrtunnman.responseXML.getElementById("globalTunnelControl")) {
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
  const runningClientCount = document.querySelector("#countClient .running");
  const runningClient = document.querySelectorAll(".cli.statusRunning");
  const runningServerCount = document.querySelector("#countServer .running");
  const runningServer = document.querySelectorAll(".svr.statusRunning");
  const standbyClientCount = document.querySelector("#countClient .standby");
  const standbyClient = document.querySelectorAll(".cli.statusStandby");
  const standbyServerCount = document.querySelector("#countServer .standby");
  const standbyServer = document.querySelectorAll(".svr.statusStandby");
  const startingClientCount = document.querySelector("#countClient .starting");
  const startingClient = document.querySelectorAll(".cli.statusStarting");
  const startingServerCount = document.querySelector("#countServer .starting");
  const startingServer = document.querySelectorAll(".svr.statusStarting");
  const stoppedClientCount = document.querySelector("#countClient .stopped");
  const stoppedClient = document.querySelectorAll(".cli.statusNotRunning");
  const stoppedServerCount = document.querySelector("#countServer .stopped");
  const stoppedServer = document.querySelectorAll(".svr.statusNotRunning");
  if (control) {
    if (runningClient && runningClient.length > 0) {
      runningClientCount.textContent = " x " + runningClient.length;
    } else if (runningClient && runningClientCount) {
      runningClientCount.textContent == "";
    }
    if (runningServer && runningServer.length > 0) {
      runningServerCount.textContent = " x " + runningServer.length;
    } else if (runningServer && runningServerCount) {
      runningServerCount.textContent = "";
    }
    if (standbyClient && standbyClient.length > 0) {
      standbyClientCount.textContent = " x " + standbyClient.length;
    } else if (standbyClient && standbyClientCount) {
      standbyClientCount.textContent = "";
    }
    if (startingClient && startingClient.length > 0) {
      startingClientCount.textContent = " x " + startingClient.length;
    } else if (startingClient && startingClientCount) {
      startingClientCount.textContent = "";
    }
    if (startingServer && startingServer.length > 0) {
      startingServerCount.textContent = " x " + startingServer.length;
    } else if (startingServer && startingServerCount) {
      startingServerCount.textContent = "";
    }
    if (stoppedClient && stoppedClient.length > 0) {
      stoppedClientCount.textContent = " x " + stoppedClient.length;
    } else if (stoppedClient && stoppedClientCount) {
      stoppedClientCount.textContent = "";
    }
    if (stoppedServer && stoppedServer.length > 0) {
      stoppedServerCount.textContent = " x " + stoppedServer.length;
    } else if (stoppedServer && stoppedServerCount) {
      stoppedServerCount.textContent = "";
    }
  }
}

function updateVolatile() {
  var updating = document.querySelectorAll(".tunnelProperties .volatile");
  var updatingResponse = xhrtunnman?.responseXML.querySelectorAll(".tunnelProperties .volatile");
  var messagesResponse = xhrtunnman?.responseXML.getElementById("tunnelMessages");
  if (messages && messagesResponse) {
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
  if (messages) {
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
document.addEventListener("DOMContentLoaded", countServices);