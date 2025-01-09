/* I2P+ miniGraph.js by dr|z3d */
/* Console sidebar minigraph renderer */
/* License: AGPL3 or later */

let isDocumentVisible = !document.hidden;
let refreshInterval = refresh !== null ? refresh * 1000 : 5000;
let minigraphRefreshIntervalId, minigraphRefreshInterval = Math.min(((refreshInterval * 3) / 2) - 500, 9500);
let lastRefreshTime = 0, refreshCount = 0;

const worker = new Worker("/js/fetchWorker.js");

function miniGraph() {
  if (!isDocumentVisible && refreshCount > 3) {
    if (minigraphRefreshIntervalId) {clearInterval(minigraphRefreshIntervalId);}
    return;
  }
  if (!minigraphRefreshIntervalId) {minigraphRefreshIntervalId = setInterval(refreshGraph, minigraphRefreshInterval);}
}

async function refreshGraph() {
  const currentTime = Date.now();
  if (refreshCount > 0 && currentTime - lastRefreshTime < refreshInterval) return;
  lastRefreshTime = currentTime;

  let graphCanvas = document.getElementById("minigraph");
  if (!graphCanvas) {return;}

  const ctx = graphCanvas.getContext("2d"),
  [minigraphWidth, minigraphHeight] = [245, 50];

  try {
    const offscreenCanvas = document.createElement("canvas");
    const offscreenCtx = offscreenCanvas.getContext("2d");

    worker.postMessage({ url: `/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=${Date.now()}` });

    return new Promise(resolve => {
      refreshCount++;
      worker.addEventListener("message", async function(event) {
        const { responseBlob, isDown } = event.data;
        if (isDown) { resolve(); return; }
        const image = new Image();
        image.src = URL.createObjectURL(responseBlob);

        image.onload = () => {
          offscreenCanvas.width = minigraphWidth;
          offscreenCanvas.height = minigraphHeight;
          offscreenCtx.clearRect(0, 0, minigraphWidth, minigraphHeight);
          offscreenCtx.drawImage(image, 0, 0);
          const parent = graphCanvas.parentNode;
          if (parent) {
            parent.replaceChild(offscreenCanvas, graphCanvas);
            offscreenCanvas.id = "minigraph";
          }
          resolve();
        };
      });
    }).then(() => { graphCanvas = document.getElementById("minigraph"); });
  } catch (error) {}
}

document.addEventListener("DOMContentLoaded", () => { refreshGraph(); miniGraph(); });
document.addEventListener("visibilitychange", () => {
  isDocumentVisible = !document.hidden;
  clearInterval(minigraphRefreshIntervalId);
  if (isDocumentVisible) {minigraphRefreshIntervalId = setInterval(refreshGraph, minigraphRefreshInterval);}
});

export { miniGraph };