/* I2P+ miniGraph.js by dr|z3d */
/* Console sidebar minigraph renderer */
/* License: AGPL3 or later */

let isDocumentVisible = !document.hidden;
let refreshInterval = refresh !== null ? refresh * 1000 : 5000;
let minigraphRefreshInterval = Math.min(((refreshInterval * 3) / 2) - 500, 9500);
let minigraphRefreshIntervalId;
let lastRefreshTime = 0;
let refreshCount = 0;

function miniGraph() {
  if (!isDocumentVisible) {
    console.log("Document not visible, not rendering graph!");
    if (minigraphRefreshIntervalId) {clearInterval(minigraphRefreshIntervalId);}
    return;
  }

  if (!minigraphRefreshIntervalId) {minigraphRefreshIntervalId = setInterval(refreshGraph, minigraphRefreshInterval);}

  refreshGraph();
  refreshCount++;
}

async function refreshGraph() {
  const currentTime = Date.now();
  if (refreshCount > 0 && currentTime - lastRefreshTime < refreshInterval) return;
  lastRefreshTime = currentTime;

  const graphCanvas = document.getElementById("minigraph"),
        ctx = graphCanvas.getContext("2d"),
        [minigraphWidth, minigraphHeight] = [245, 50];

  try {
    const response = await fetch(`/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=${Date.now()}`);
    if (!response.ok) throw new Error("Network error");
    const imageBlob = await response.blob();
    const image = new Image();
    image.src = URL.createObjectURL(imageBlob);
    Object.assign(ctx, {imageSmoothingEnabled: false, globalCompositeOperation: "source-out", globalAlpha: 1});

    return new Promise(resolve => {
      image.onload = () => {
        graphCanvas.width = minigraphWidth;
        graphCanvas.height = minigraphHeight;
        requestAnimationFrame(() => {
          ctx.drawImage(image, 0, 0);
          resolve();
        });
      };
    });
  } catch (error) {}
}

document.addEventListener("DOMContentLoaded", miniGraph);
document.addEventListener("visibilitychange", () => {
  isDocumentVisible = !document.hidden;
  if (!isDocumentVisible && minigraphRefreshIntervalId) {clearInterval(minigraphRefreshIntervalId);}
  else {miniGraph();}
});

export { miniGraph };