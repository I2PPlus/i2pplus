/* I2P+ fetchWorker.js by dr|z3d */
/* A general purpose worker for background fetch requests */
/* License: AGPLv3 or later */

const MAX_CONCURRENT_REQUESTS = 16;
const MAX_QUEUE_SIZE = 16;
const MIN_INTERVAL = 500;
const DEBOUNCE_DELAY = 100;
const responseCountMap = new Map();
const clientId = Math.random().toString(36).substr(2, 9);

let activeRequests = 0;
let fetchQueue = [];
let noResponse = 0;
let debounceTimeouts = new Map();

self.onconnect = function(e) {
  const port = e.ports[0];
  const clientData = { port, clientId };
  port.onmessage = function(event) {handleClientMessage(event, clientData);};
  port.onclose = () => {cleanupClient(clientId);};
};

function handleClientMessage(event, clientData) {
  const { url, force = false } = event.data;
  const now = Date.now();
  const lastRequestTime = responseCountMap.get(clientId)?.lastRequestTime || 0;

  if (!force && (now - lastRequestTime < MIN_INTERVAL)) return;
  if (fetchQueue.length >= MAX_QUEUE_SIZE) {return;}
  if (force) {enqueueFetchRequest(url, now, clientData);}
  else if (debounceTimeouts.has(url)) {clearTimeout(debounceTimeouts.get(url));}

  debounceTimeouts.set(url, setTimeout(() => {
    enqueueFetchRequest(url, now, clientData);
    debounceTimeouts.delete(url);
  }, DEBOUNCE_DELAY));
}

function enqueueFetchRequest(url, now, clientData) {
  if (activeRequests < MAX_CONCURRENT_REQUESTS) {
    activeRequests++;
    processFetchRequest(url, now, clientData);
  } else {
    fetchQueue.push({ url, now, clientData });
  }
}

async function processFetchRequest(url, now, clientData) {
  const {port, clientId} = clientData;
  try {
    const response = await fetch(url);
    let messagePayload = {responseBlob: null, isDown: false, noResponse: 0};

    if (response.ok) {
      const contentType = response.headers.get("Content-Type");
      if (contentType.includes("text/html")) {
        messagePayload.responseText = await response.text();
      } else {
        messagePayload.responseBlob = await response.blob();
      }
      updateLastRequestTime(clientId, now);
    } else {
      messagePayload.isDown = true;
      incrementNoResponse();
    }

    port.postMessage(messagePayload);
  } catch (error) {
    port.postMessage({ responseBlob: null, isDown: true, noResponse: incrementNoResponse() });
  } finally {
    decrementActiveRequests();
    processNextFetchRequest();
  }
}

function updateLastRequestTime(clientId, now) {
  responseCountMap.set(clientId, { lastRequestTime: now });
}

function incrementNoResponse() {return ++noResponse;}

function decrementActiveRequests() {activeRequests--;}

function processNextFetchRequest() {
  while (activeRequests < MAX_CONCURRENT_REQUESTS && fetchQueue.length > 0) {
    const { url, now, clientData } = fetchQueue.shift();
    activeRequests++;
    processFetchRequest(url, now, clientData);
  }
}

function cleanupClient(clientId) {
  responseCountMap.delete(clientId);
  fetchQueue = fetchQueue.filter(item => item.clientData.clientId !== clientId);
}