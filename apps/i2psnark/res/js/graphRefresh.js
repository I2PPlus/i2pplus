/* I2PSnark graphRefresh.js by dr|3d */
/* Update snark download graph dynamically and apply to message log background if available */
/* License: AGPL3 or later */

let lastSnarkGraphRefresh = 0;
let graphEnabled = true;
let refreshCache = new Map();
const noload = document.getElementById("noload");

function refreshGraph() {
  const now = Date.now();
  const graphcss = document.getElementById("graphcss");
  const graphUrlBase = "/viewstat.jsp?stat=[I2PSnark] InBps&showEvents=false&" +
                       "period=60000&periodCount=1440&end=0&width=2000&height=160&hideLegend=true&" +
                       "hideTitle=true&hideGrid=true&";
  if (now - lastSnarkGraphRefresh < 5 * 60 * 1000 || !graphEnabled) {return;}
  lastSnarkGraphRefresh = now;
  const graphUrl = graphUrlBase + "t=" + now;
  if (refreshCache.has(graphUrl)) {
    graphcss.innerText = ":root{--snarkGraph:url(" + refreshCache.get(graphUrl) + ")}";
    return;
  }
  fetch(graphUrl).then(response => {
    if (response.ok) {return response.blob();}
    else if (response.status === 400) {
      graphEnabled = false;
      throw new Error("400 Bad Request");
    }
    throw new Error('Network response was not ok');
  }).then(blob => {
    const graphDataUrl = URL.createObjectURL(blob);
    graphcss.innerText = ":root{--snarkGraph:url('" + graphDataUrl + "')}";
    refreshCache.set(graphUrl, `url("${graphDataUrl}")`);
    setTimeout(() => refreshCache.delete(graphUrl), 5*60*1000);
  }).catch(error => {
    if (error.message === "400 Bad Request" || !graphEnabled) {
      if (graphcss) {graphcss.textContent = "";}
      else {
        const graphcss = document.createElement("style");
        graphcss.id = "graphcss";
        graphcss.textContent = ":root{--snarkGraph{url()}";
        document.body.appendChild(graphcss);
      }
      return;
    }
  });
}

let snarkGraphIntervalId;
if (!snarkGraphIntervalId) {
  const intervalTime = noload ? 5 * 60 * 1000 : 15 * 60 * 1000;
  snarkGraphIntervalId = setInterval(refreshGraph, intervalTime);
}

function clearIntervalAndAbort() {clearInterval(snarkGraphIntervalId);}

window.addEventListener("beforeunload", clearIntervalAndAbort);
document.addEventListener("DOMContentLoaded", refreshGraph);