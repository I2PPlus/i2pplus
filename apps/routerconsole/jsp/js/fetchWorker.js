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

self.onconnect = function(e) {
  const port = e.ports[0];
  const clientId = Math.random().toString(36).substr(2, 9);
  const clientData = { port, clientId };

  port.onmessage = function(event) {
    const { url, force = false } = event.data;
    const now = Date.now();
    const lastRequestTime = responseCountMap.get(clientId)?.lastRequestTime || 0;

    if (!force && (now - lastRequestTime < MIN_INTERVAL)) {return;}

    if (debounceTimeouts.has(url)) {
      clearTimeout(debounceTimeouts.get(url));
    }

    debounceTimeouts.set(url, setTimeout(() => {
      if (activeRequests < MAX_CONCURRENT_REQUESTS) {
        activeRequests++;
        processFetchRequest(url, now, clientData);
      } else {
        fetchQueue.push({ url, now, clientData });
      }
    }, DEBOUNCE_DELAY));
  };
};

async function processFetchRequest(url, now, clientData) {
  const { port, clientId } = clientData;
  try {
    const response = await fetch(url);
    let messagePayload = { responseBlob: null, isDown: false, noResponse: 0 };

    if (response.ok) {
      const contentType = response.headers.get("Content-Type");
      if (contentType.includes("text/html")) {
        messagePayload.responseText = await response.text();
      } else {
        messagePayload.responseBlob = await response.blob();
      }
      responseCountMap.set(clientId, { lastRequestTime: now });
    } else {
      messagePayload.isDown = true;
      noResponse++;
    }

    port.postMessage(messagePayload);
  } catch (error) {
    port.postMessage({ responseBlob: null, isDown: true, noResponse: noResponse + 1 });
    noResponse++;
  } finally {
    activeRequests--;
    processNextFetchRequest();
  }
}

function processNextFetchRequest() {
  while (activeRequests < MAX_CONCURRENT_REQUESTS && fetchQueue.length > 0) {
    const { url, now, clientData } = fetchQueue.shift();
    activeRequests++;
    processFetchRequest(url, now, clientData);
  }
}