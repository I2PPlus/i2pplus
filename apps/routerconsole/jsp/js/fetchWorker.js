/* I2P+ fetchWorker.js by dr|z3d */
/* A general purpose worker for background fetch requests */
/* License: AGPLv3 or later */

const MAX_CONCURRENT_REQUESTS = 8;
const MIN_INTERVAL = 1000;
let activeRequests = 0;
let fetchQueue = [];
let noResponse = 0;
const lastRequestTimeMap = new Map();

self.addEventListener("message", async function(event) {
  const { url, force = false } = event.data;
  const now = Date.now();
  const lastRequestTime = lastRequestTimeMap.get(url);

  if (!force && lastRequestTime !== undefined && now - lastRequestTime < MIN_INTERVAL) {return;}

  if (activeRequests < MAX_CONCURRENT_REQUESTS) {
    activeRequests++;
    processFetchRequest(url);
  } else {fetchQueue.push(url);}
});

async function processFetchRequest(url) {
  const now = Date.now();
  try {
    const response = await fetch(url, { method: "GET", headers: { Accept: "text/html, application/octet-stream" }, });

    if (response.ok) {
      if (response.headers.get("Content-Type").includes("text/html")) {
        const responseText = await response.text();
        self.postMessage({ responseText, isDown: false, noResponse: 0 });
      } else {
        const responseBlob = await response.blob();
        self.postMessage({ responseBlob, isDown: false, noResponse: 0 });
      }
      noResponse = 0;
    } else {
      self.postMessage({ responseBlob: null, isDown: true, noResponse: noResponse + 1 });
      noResponse++;
    }
  } catch (error) {
     setTimeout(() => { self.postMessage({ responseBlob: null, isDown: true, noResponse: noResponse + 1 }); }, 3000);
     noResponse++;
  } finally {
    activeRequests--;
    processNextFetchRequest();
    lastRequestTimeMap.set(url, now);
  }
}

function processNextFetchRequest() {
  if (fetchQueue.length > 0 && activeRequests < MAX_CONCURRENT_REQUESTS) {
    const url = fetchQueue.shift();
    activeRequests++;
    processFetchRequest(url);
  }
}