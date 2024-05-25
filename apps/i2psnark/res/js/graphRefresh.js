/* I2PSnark graphRefresh.js by dr|3d */
/* Update snark download graph dynamically and apply to message log background if available */
/* License: AGPL3 or later */

// track last refresh time
let lastSnarkGraphRefresh = 0;

function refreshGraph() {
  const now = Date.now();
  const graphcss = document.getElementById("graphcss");
  const noload = document.getElementById("noload");
  const graphUrlBase = "/viewstat.jsp?stat=[I2PSnark] InBps&showEvents=false&" +
                       "period=60000&periodCount=1440&end=0&width=2000&height=160&hideLegend=true&" +
                       "hideTitle=true&hideGrid=true&";

  // check if at least 5 minutes has elapsed since last refresh
  if (now - lastSnarkGraphRefresh < 5*60*1000) {return;}

  // update last refresh time
  lastSnarkGraphRefresh = now;

  // update graph URL with current timestamp
  var graphUrl = graphUrlBase + "t=" + now;

  const xhrSnarkGraph = new XMLHttpRequest();
  xhrSnarkGraph.open("GET", graphUrl, true);
  xhrSnarkGraph.onload = function() {
    if (xhrSnarkGraph.status == 200 && graphcss) {
      graphcss.innerText = ":root{--snarkGraph:url('" + graphUrl + "')}";
    }
  };
  xhrSnarkGraph.send();

  function clearIntervalAndAbort() {
    clearInterval(snarkGraphIntervalId);
    if (xhrSnarkGraph.readyState !== 4) {xhrSnarkGraph.abort();}
  }

  if (!snarkGraphIntervalId) {var snarkGraphIntervalId = setInterval(refreshGraph, noload ? 5*60*1000 : 15*60*1000);}
  window.addEventListener("beforeunload", clearIntervalAndAbort);
}

document.addEventListener("DOMContentLoaded", refreshGraph);