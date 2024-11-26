/* I2P+ miniGraph.js by dr|z3d */
/* Console sidebar minigraph renderer */
/* License: AGPL3 or later */

import { refreshInterval } from "/js/initSidebar.js";

let lastRefreshTime = 0, refreshCount = 0, MIN_REFRESH_INTERVAL = 10000;

function miniGraph() {
  const graphCanvas = document.getElementById("minigraph"),
                      ctx = graphCanvas.getContext("2d"),
                      graphContainer = document.getElementById("sb_graphcontainer"),
                      graphContainerHR = document.querySelector("#sb_graphcontainer+hr"),
                      [minigraphWidth, minigraphHeight] = [245, 50];
  const refreshGraph = (() => {
    return async () => {
      const currentTime = Date.now();
      if (refreshCount > 0 && currentTime - lastRefreshTime < Math.min(refreshInterval*3/2, MIN_REFRESH_INTERVAL)) return;
      lastRefreshTime = currentTime;
      if (graphContainer && graphContainer.hidden) graphContainer.hidden = graphContainerHR.hidden = false;
      const response = await fetch(`/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=${Date.now()}`);
      if (!response.ok) return;
      const imageBlob = await response.blob();
      if ("OffscreenCanvas" in window) {
        const offscreen = new OffscreenCanvas(minigraphWidth, minigraphHeight), offscreenCtx = offscreen.getContext("2d");
        Object.assign(offscreenCtx, { imageSmoothingEnabled: false, globalCompositeOperation: "source-out", globalAlpha: 1 });
        const image = new Image();
        image.src = URL.createObjectURL(imageBlob);
        image.onload = () => {
          requestAnimationFrame(() => {
            offscreenCtx.drawImage(image, 0, 0);
            ctx.clearRect(0, 0, minigraphWidth, minigraphHeight);
            ctx.drawImage(offscreen, 0, 0, minigraphWidth, minigraphHeight);
          });
        };
      } else {
        const image = new Image(minigraphWidth, minigraphHeight);
        image.src = URL.createObjectURL(imageBlob);
        return new Promise((resolve) => {
          image.onload = () => {
            graphCanvas.width = minigraphWidth;
            graphCanvas.height = minigraphHeight;
            ctx.drawImage(image, 0, 0);
            resolve();
          };
          image.onerror = () => {};
        });
      }
    };
  })();
  refreshGraph();
  refreshCount++;
}
export { miniGraph };