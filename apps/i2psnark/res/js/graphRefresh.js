/**
 * @module graphRefresh
 * @file graphRefresh.js - Dynamically refreshes the I2PSnark download speed graph.
 * @description Fetches a bandwidth graph image from the I2P stats servlet at a configurable
 * interval, converts it to a blob URL, and applies it as a CSS custom property so the UI
 * can display a live download graph. Also applies the graph as a background in the message
 * log when available. Caches responses and auto-disables on HTTP 400 errors.
 * @author dr|3d
 * @license AGPL3 or later
 */

/**
 * @type {number}
 * @description Timestamp of the last graph refresh in milliseconds.
 */
let lastSnarkGraphRefresh = 0;

/**
 * @type {boolean}
 * @description Whether graph fetching is enabled; set to false on HTTP 400 errors.
 */
let graphEnabled = true;

/**
 * @type {Map<string, string>}
 * @description Cache mapping graph URLs to their blob/URL representations.
 */
let refreshCache = new Map();

/**
 * @type {number}
 * @description Counter tracking how many times the graph has been refreshed.
 */
let refreshCount = 0;

/**
 * @type {?HTMLElement}
 * @description The #noload element, used to determine refresh interval timing.
 */
const noload = document.getElementById("noload");

/**
 * @function refreshGraph
 * @description Fetches the I2PSnark bandwidth graph image if the refresh interval has elapsed
 * and graph fetching is enabled. Caches the result as a blob URL and applies it to the
 * CSS custom property --snarkGraph. Disables graph fetching on HTTP 400 errors.
 * @returns {void}
 * @example
 * // Manually trigger a graph refresh
 * refreshGraph();
 */
function refreshGraph() {
  const now = Date.now();
  const graphcss = document.getElementById("graphcss");
  const graphUrlBase = "/viewstat.jsp?stat=[I2PSnark] InBps&showEvents=false&" +
                       "period=60000&periodCount=1440&end=0&width=2000&height=160&hideLegend=true&" +
                       "hideTitle=true&hideGrid=true&";
  if ((now - lastSnarkGraphRefresh < 5 * 60 * 1000 && refreshCount !== 0) || !graphEnabled) {return;}
  lastSnarkGraphRefresh = now;
  const graphUrl = graphUrlBase + "t=" + now;
  if (refreshCache.has(graphUrl)) {
    graphcss.innerText = ":root{--snarkGraph:url(" + refreshCache.get(graphUrl) + ")}";
    return;
  }
  fetch(graphUrl).then(response => {
    if (response.ok) {return response.blob();}
    if (response.status === 400) {
      graphEnabled = false;
      throw new Error("400 Bad Request");
    }
    throw new Error("Network response was not ok");
  }).then(blob => {
    const graphDataUrl = URL.createObjectURL(blob);
    graphcss.innerText = ":root{--snarkGraph:url('" + graphDataUrl + "')}";
    refreshCache.set(graphUrl, `url("${graphDataUrl}")`);
    setTimeout(() => refreshCache.delete(graphUrl), 5*60*1000);
    refreshCount++;
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

/**
 * @type {?number}
 * @description The interval ID for the periodic graph refresh timer.
 */
let snarkGraphIntervalId;

if (!snarkGraphIntervalId) {
  const intervalTime = noload ? 5 * 60 * 1000 : 15 * 60 * 1000;
  snarkGraphIntervalId = setInterval(refreshGraph, intervalTime);
}

/**
 * @function clearIntervalAndAbort
 * @description Clears the graph refresh interval timer. Called on page unload to prevent
 * memory leaks and unnecessary network requests.
 * @returns {void}
 */
function clearIntervalAndAbort() {clearInterval(snarkGraphIntervalId);}

window.addEventListener("beforeunload", clearIntervalAndAbort);
document.addEventListener("DOMContentLoaded", refreshGraph);