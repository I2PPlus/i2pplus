/* I2PSnark graphRefresh.js by dr|3d */
/* Update snark download graph dynamically and apply to message log background if available */
/* License: AGPL3 or later */

var graphcss = document.getElementById("graphcss");
var noload = document.getElementById("noload");
var xhrGraph;
var graphUrl = "/viewstat.jsp?stat=[I2PSnark] InBps&showEvents=false&" +
               "period=60000&periodCount=1440&end=0&width=2000&height=160&hideLegend=true&" +
               "hideTitle=true&hideGrid=true&t=";

function refreshGraph() {
  var now = new Date().getTime();

  xhrGraph = new XMLHttpRequest();
  xhrGraph.open("GET", graphUrl + now, true);
  xhrGraph.onload = function() {
    if (xhrGraph.status == 200 && graphcss) {
      graphcss.innerText = ":root{--snarkGraph:url('" + graphUrl + now + "')}";
    }
  };
  xhrGraph.send();

  function clearIntervalAndAbort() {
    clearInterval(intervalId);
    if (xhrGraph.readyState !== 4) {
      xhrGraph.abort();
    }
  }

  var intervalId = setInterval(refreshGraph, noload ? 5*60*1000 : 15*60*1000);
  window.addEventListener('beforeunload', clearIntervalAndAbort);
}

document.addEventListener("DOMContentLoaded", refreshGraph);