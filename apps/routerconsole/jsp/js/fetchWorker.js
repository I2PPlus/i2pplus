/**
 * @module fetchWorker
 * @description A general-purpose SharedWorker for background fetch requests with
 * concurrency control, debouncing, and queue management. Handles both HTML text
 * and binary blob responses.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

/** @type {number} */
const MAX_CONCURRENT_REQUESTS = 16;
/** @type {number} */
const MAX_QUEUE_SIZE = 16;
/** @type {number} */
const MIN_INTERVAL = 500;
/** @type {number} */
const DEBOUNCE_DELAY = 200;
/** @type {Map<string, {lastRequestTime: number}>} */
const responseCountMap = new Map();
/** @type {string} */
const clientId = Math.random().toString(36).substr(2, 9);

/** @type {number} */
let activeRequests = 0;
/** @type {Array<{url: string, now: number, clientData: Object}>} */
let fetchQueue = [];
/** @type {number} */
let noResponse = 0;
/** @type {Map<string, number>} */
let debounceTimeouts = new Map();

/**
 * Handles new SharedWorker connections by setting up message and close handlers.
 * @function self.onconnect
 * @param {MessageEvent} e - The connection event containing ports
 * @returns {void}
 */
self.onconnect = function(e) {
  const port = e.ports[0];
  const clientData = { port, clientId };
  port.onmessage = function(event) {handleClientMessage(event, clientData);};
  port.onclose = () => {cleanupClient(clientId);};
};

/**
 * Processes incoming messages from a client port, debouncing or force-enqueueing fetch requests.
 * @function handleClientMessage
 * @param {MessageEvent} event - The message event containing fetch data
 * @param {Object} clientData - Client data with port and clientId
 * @param {MessagePort} clientData.port - The communication port
 * @param {string} clientData.clientId - The client's unique identifier
 * @returns {void}
 */
function handleClientMessage(event, clientData) {
  const { url, force = false } = event.data;
  const now = Date.now();
  const lastRequestTime = responseCountMap.get(clientId)?.lastRequestTime || 0;

  if (!force && (now - lastRequestTime < MIN_INTERVAL)) { return; }
  if (fetchQueue.length >= MAX_QUEUE_SIZE) { return; }
  if (force) {enqueueFetchRequest(url, now, clientData);}
  else if (debounceTimeouts.has(url)) {clearTimeout(debounceTimeouts.get(url));}

  debounceTimeouts.set(url, setTimeout(() => {
    enqueueFetchRequest(url, now, clientData);
    debounceTimeouts.delete(url);
  }, DEBOUNCE_DELAY));
}

/**
 * Enqueues a fetch request, executing immediately if concurrency limit not reached.
 * @function enqueueFetchRequest
 * @param {string} url - The URL to fetch
 * @param {number} now - Timestamp of the request
 * @param {Object} clientData - Client data with port and clientId
 * @returns {void}
 */
function enqueueFetchRequest(url, now, clientData) {
  if (activeRequests < MAX_CONCURRENT_REQUESTS) {
    activeRequests++;
    processFetchRequest(url, now, clientData);
  } else {
    fetchQueue.push({ url, now, clientData });
  }
}

/**
 * Executes a fetch request and posts the response back to the client port.
 * @async
 * @function processFetchRequest
 * @param {string} url - The URL to fetch
 * @param {number} now - Timestamp of the request
 * @param {Object} clientData - Client data with port and clientId
 * @returns {Promise<void>}
 */
async function processFetchRequest(url, now, clientData) {
  const {port, clientId} = clientData;
  try {
    const response = await fetch(url);
    let messagePayload;

    if (response.ok) {
      const contentType = response.headers.get("Content-Type");
      if (contentType && contentType.includes("text/html")) {
        const responseText = await response.text();
        messagePayload = { url, responseText, isDown: false, noResponse: 0 };
      } else {
        const responseBlob = await response.blob();
        messagePayload = { url, responseBlob, isDown: false, noResponse: 0 };
      }
      updateLastRequestTime(clientId, now);
    } else {
      messagePayload = { url, isDown: true, noResponse: incrementNoResponse() };
    }

    port.postMessage(messagePayload);
  } catch (error) {
    port.postMessage({ url, isDown: true, noResponse: incrementNoResponse() });
  } finally {
    decrementActiveRequests();
    processNextFetchRequest();
  }
}

/**
 * Updates the last request timestamp for a given client.
 * @function updateLastRequestTime
 * @param {string} clientId - The client's unique identifier
 * @param {number} now - The current timestamp
 * @returns {void}
 */
function updateLastRequestTime(clientId, now) {
  responseCountMap.set(clientId, { lastRequestTime: now });
}

/**
 * Increments and returns the no-response counter.
 * @function incrementNoResponse
 * @returns {number} The incremented counter value
 */
function incrementNoResponse() {return ++noResponse;}

/**
 * Decrements the active request counter.
 * @function decrementActiveRequests
 * @returns {void}
 */
function decrementActiveRequests() {activeRequests--;}

/**
 * Processes the next fetch request in the queue if concurrency limit allows.
 * @function processNextFetchRequest
 * @returns {void}
 */
function processNextFetchRequest() {
  while (activeRequests < MAX_CONCURRENT_REQUESTS && fetchQueue.length > 0) {
    const { url, now, clientData } = fetchQueue.shift();
    activeRequests++;
    processFetchRequest(url, now, clientData);
  }
}

/**
 * Cleans up resources associated with a disconnected client.
 * @function cleanupClient
 * @param {string} clientId - The client's unique identifier
 * @returns {void}
 */
function cleanupClient(clientId) {
  responseCountMap.delete(clientId);
  fetchQueue = fetchQueue.filter(item => item.clientData.clientId !== clientId);
}