/* I2P+ miniGraph.js by dr|z3d */
/* Console sidebar minigraph renderer */
/* License: AGPL3 or later */

import { refreshInterval } from "/js/initSidebar.js";

let lastRefreshTime = 0;
let refreshCount = 0;
const MIN_REFRESH_INTERVAL = 10000;

function miniGraph() {
  const graphCanvas = document.getElementById("minigraph");
  const ctx = graphCanvas?.getContext("2d");
  const graphContainer = document.getElementById("sb_graphcontainer");
  const graphContainerHR = document.querySelector("#sb_graphcontainer+hr");
  const [minigraphWidth, minigraphHeight] = [245, 50];

  if (ctx) {Object.assign(ctx, { imageSmoothingEnabled: false, globalCompositeOperation: "copy", globalAlpha: 1 });}

  const refreshGraph = (() => {
    return async () => {
      const currentTime = Date.now();
      if (refreshCount > 0 && currentTime - lastRefreshTime < Math.min(refreshInterval * 2, MIN_REFRESH_INTERVAL)) {return;}
      lastRefreshTime = currentTime;

      if (graphContainer && graphContainer.hidden) {graphContainer.hidden = graphContainerHR.hidden = false;}
      const response = await fetch(`/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=${Date.now()}`);
      if (!response.ok) {return;}
      const image = new Image(minigraphWidth, minigraphHeight);
      image.src = URL.createObjectURL(await response.blob());
      return new Promise((resolve) => {
        image.onload = () => {
          graphCanvas.width = minigraphWidth;
          graphCanvas.height = minigraphHeight;
          ctx?.drawImage(image, 0, 0);
          resolve();
        };
        image.onerror = () => {};
      });
    };
  })();
  refreshGraph();
  refreshCount++;
}

export { miniGraph };