/* I2PSnark graphRefresh.js by dr|3d */
/* Update snark download graph dynamically and apply to message log background if available */
/* License: AGPL3 or later */

var graphcss = document.getElementById("graphcss");
var noload = document.getElementById("noload");
var xhrGraph;
var graphUrlBase = "/viewstat.jsp?stat=[I2PSnark] InBps&showEvents=false&" +
               "period=60000&periodCount=1440&end=0&width=2000&height=160&hideLegend=true&" +
               "hideTitle=true&hideGrid=true&";

// track last refresh time
var lastRefresh = 0;

function refreshGraph() {
  var now = Date.now();

  // check if at least 5 minutes has elapsed since last refresh
  if (now - lastRefresh < 5*60*1000) {
    return;
  }

  // update last refresh time
  lastRefresh = now;

  // update graph URL with current timestamp
  var graphUrl = graphUrlBase + "t=" + now;

  xhrGraph = new XMLHttpRequest();
  xhrGraph.open("GET", graphUrl, true);
  xhrGraph.onload = function() {
    if (xhrGraph.status == 200 && graphcss) {
      graphcss.innerText = ":root{--snarkGraph:url('" + graphUrl + "')}";
    }
  };
  xhrGraph.send();

  function clearIntervalAndAbort() {
    clearInterval(graphIntervalId);
    if (xhrGraph.readyState !== 4) {
      xhrGraph.abort();
    }
  }

  if (!graphIntervalId) {
    var graphIntervalId = setInterval(refreshGraph, noload ? 5*60*1000 : 15*60*1000);
  }
  window.addEventListener('beforeunload', clearIntervalAndAbort);
}

document.addEventListener("DOMContentLoaded", refreshGraph);