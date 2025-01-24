/* I2P+ miniGraph.js by dr|z3d */
/* Console sidebar minigraph renderer */
/* License: AGPL3 or later */

let refreshInterval = refresh !== null ? Math.max(refresh * 1000, 1000) : 5000;
let minigraphRefreshIntervalId, minigraphRefreshInterval = Math.min(((refreshInterval * 3) / 2) - 500, 9500);
let lastRefreshTime = 0;
const isDocumentVisible = !document.hidden;
const offscreenCanvas = document.createElement("canvas");
const worker = new SharedWorker("/js/fetchWorker.js");

function miniGraph() {
  worker.port.start();
  worker.port.addEventListener("message", handleWorkerMessage);
  worker.port.postMessage({ type: "connect" });
}

function handleWorkerMessage(event) {
  if (event.data.type === "connected" && !minigraphRefreshIntervalId) {
    minigraphRefreshIntervalId = setInterval(refreshGraph, refreshInterval);
  }
  if (event.data.responseBlob) {
    handleGraphUpdate(event.data.responseBlob);
  }
}

async function refreshGraph() {
  const currentTime = Date.now();
  if (currentTime - lastRefreshTime >= refreshInterval) {
    lastRefreshTime = currentTime;
    const graphCanvas = document.getElementById("minigraph");
    if (!graphCanvas) return;
    worker.port.postMessage({ url: `/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=${Date.now()}` });
  }
}

function handleGraphUpdate(responseBlob) {
  const image = new Image();
  image.src = URL.createObjectURL(responseBlob);
  image.onload = () => {
    const graphCanvas = document.getElementById("minigraph");
    if (graphCanvas) {
      const offscreenCtx = offscreenCanvas.getContext("2d");
      offscreenCanvas.width = 245;
      offscreenCanvas.height = 50;
      offscreenCtx.clearRect(0, 0, 245, 50);
      offscreenCtx.drawImage(image, 0, 0);
      const parent = graphCanvas.parentNode;
      if (parent) {
        parent.replaceChild(offscreenCanvas, graphCanvas);
        offscreenCanvas.id = "minigraph";
      }
    }
  };
}

document.addEventListener("DOMContentLoaded", () => {
  miniGraph();
  refreshGraph();
  minigraphRefreshIntervalId = setInterval(refreshGraph, refreshInterval);
});

document.addEventListener("visibilitychange", () => {
  if (!isDocumentVisible) {clearInterval(minigraphRefreshIntervalId);}
  else {minigraphRefreshIntervalId = setInterval(refreshGraph, refreshInterval);}
});

export {miniGraph};