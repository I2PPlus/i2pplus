/* I2P+ miniGraph.js by dr|z3d */
/* Console sidebar minigraph renderer */
/* License: AGPL3 or later */

let isDocumentVisible = !document.hidden;
let refreshInterval = refresh !== null ? refresh * 1000 : 5000;
let minigraphRefreshIntervalId, minigraphRefreshInterval = Math.min(((refreshInterval * 3) / 2) - 500, 9500);
let lastRefreshTime = 0, refreshCount = 0;

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

  const graphCanvas = document.getElementById("minigraph");
  if (!graphCanvas) {return;}

  const ctx = graphCanvas.getContext("2d"),
  [minigraphWidth, minigraphHeight] = [245, 50];

  try {
    const response = await fetch(`/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=${Date.now()}`);
    if (!response.ok) throw new Error("Network error");
    const imageBlob = await response.blob();
    const image = new Image();
    image.src = URL.createObjectURL(imageBlob);
    Object.assign(ctx, {imageSmoothingEnabled: false, globalCompositeOperation: "source-out", globalAlpha: 1});

    return new Promise(resolve => {
      refreshCount++;
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

document.addEventListener("DOMContentLoaded", () => { refreshGraph(); miniGraph(); });
document.addEventListener("visibilitychange", () => {
  isDocumentVisible = !document.hidden;
  clearInterval(minigraphRefreshIntervalId);
  if (isDocumentVisible) {minigraphRefreshIntervalId = setInterval(refreshGraph, minigraphRefreshInterval);}
});

export { miniGraph };