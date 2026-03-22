/**
 * @module miniGraph
 * @description Renders a mini bandwidth graph in the console sidebar using
 * a SharedWorker for background fetch requests and canvas rendering for
 * efficient updates.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/** @type {number} */
let refreshInterval = refresh !== null ? Math.max(refresh * 1000, 1000) : 5000;
/** @type {number|undefined} */
let minigraphRefreshIntervalId, minigraphRefreshInterval = Math.min(((refreshInterval * 3) / 2) - 500, 9500);
/** @type {number} */
let lastRefreshTime = 0;
/** @type {boolean} */
const isDocumentVisible = !document.hidden;
/** @type {HTMLCanvasElement} */
const offscreenCanvas = document.createElement("canvas");
/** @type {SharedWorker} */
const worker = new SharedWorker("/js/fetchWorker.js");

/**
 * Initializes the mini graph by starting the SharedWorker connection and
 * setting up the message handler.
 * @function miniGraph
 * @returns {void}
 */
function miniGraph() {
  worker.port.start();
  worker.port.addEventListener("message", handleWorkerMessage);
  worker.port.postMessage({ type: "connect" });
}

/**
 * Handles messages from the SharedWorker, initiating refresh intervals
 * and processing graph image updates.
 * @function handleWorkerMessage
 * @param {MessageEvent} event - The message event from the SharedWorker
 * @returns {void}
 */
function handleWorkerMessage(event) {
  if (event.data.type === "connected" && !minigraphRefreshIntervalId) {
    minigraphRefreshIntervalId = setInterval(refreshGraph, refreshInterval);
  }
  if (event.data.responseBlob) {
    handleGraphUpdate(event.data.responseBlob);
  }
}

/**
 * Sends a fetch request to the worker for the latest bandwidth graph image.
 * @async
 * @function refreshGraph
 * @returns {Promise<void>}
 */
async function refreshGraph() {
  const currentTime = Date.now();
  if (currentTime - lastRefreshTime >= refreshInterval) {
    lastRefreshTime = currentTime;
    const graphCanvas = document.getElementById("minigraph");
    if (!graphCanvas) { return; }
    worker.port.postMessage({ url: `/viewstat.jsp?stat=bw.combined&periodCount=20&width=250&height=50&hideLegend=true&hideGrid=true&hideTitle=true&t=${Date.now()}` });
  }
}

/**
 * Processes a graph image blob from the worker and renders it to the offscreen canvas.
 * @function handleGraphUpdate
 * @param {Blob} responseBlob - The graph image blob data
 * @returns {void}
 */
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