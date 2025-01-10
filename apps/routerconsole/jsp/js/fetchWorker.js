/* I2P+ fetchWorker.js by dr|z3d */
/* A general purpose worker for background fetch requests */
/* License: AGPLv3 or later */

const MAX_CONCURRENT_REQUESTS = 8;
const MIN_INTERVAL = 950;
const DEBOUNCE_DELAY = 500;

let activeRequests = 0;
let fetchQueue = [];
let noResponse = 0;
const responseCountMap = new Map();
let debounceTimeouts = new Map();

self.addEventListener("message", (event) => {
  const { url, force = false } = event.data;
  const now = Date.now();
  const lastRequestTime = responseCountMap.get(url)?.lastRequestTime || 0;

  if (!force && (now - lastRequestTime < MIN_INTERVAL)) {return;}

  if (debounceTimeouts.has(url)) {
    clearTimeout(debounceTimeouts.get(url));
  }

  debounceTimeouts.set(url, setTimeout(() => {
    if (activeRequests < MAX_CONCURRENT_REQUESTS) {
      activeRequests++;
      processFetchRequest(url, now);
    } else {fetchQueue.push({ url, now });}
  }, DEBOUNCE_DELAY));
});

async function processFetchRequest(url, now) {
  try {
    const response = await fetch(url);
    let messagePayload = { responseBlob: null, isDown: false, noResponse: 0 };

    if (response.ok) {
      const contentType = response.headers.get("Content-Type");
      if (contentType.includes("text/html")) {messagePayload.responseText = await response.text();}
      else {messagePayload.responseBlob = await response.blob();}
      responseCountMap.delete(url);
    } else {
      messagePayload.isDown = true;
      noResponse++;
    }
    self.postMessage(messagePayload);
  } catch (error) {
    self.postMessage({ responseBlob: null, isDown: true, noResponse: noResponse + 1 });
    noResponse++;
  } finally {
    activeRequests--;
    processNextFetchRequest();
    responseCountMap.set(url, { lastRequestTime: now });
  }
}

function processNextFetchRequest() {
  while (activeRequests < MAX_CONCURRENT_REQUESTS && fetchQueue.length > 0) {
    const { url, now } = fetchQueue.shift();
    activeRequests++;
    processFetchRequest(url, now);
  }
}