/**
 * @module snarkWork
 * @file snarkWork.js - Web Worker for network requests in I2PSnark.
 * @description Runs as a Web Worker to handle fetch requests for HTML documents off the
 * main thread. Supports streaming reads, DOM parsing, and abort/cancellation via
 * AbortController. Communicates with the main thread through postMessage using
 * the MESSAGE_TYPES protocol.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {MESSAGE_TYPES} from "./messageTypes.js";

/**
 * @type {AbortController}
 * @description Controller used to abort in-flight fetch requests when a cancellation is received.
 */
let abortController = new AbortController();

/**
 * @description Listens for messages from the main thread. Handles FETCH_HTML_DOCUMENT requests
 * by performing a streaming fetch, parsing the response as HTML, and posting the resulting
 * Document back. Handles ABORT requests by aborting the current controller and resetting it.
 * @param {MessageEvent} event - The message event from the main thread.
 * @param {Object} event.data - The message payload.
 * @param {string} event.data.type - The message type from MESSAGE_TYPES.
 * @param {string} [event.data.url] - The URL to fetch (for FETCH_HTML_DOCUMENT messages).
 * @fires postMessage - Sends FETCH_HTML_DOCUMENT_RESPONSE, CANCELLED, ERROR, or ABORTED messages.
 */
self.addEventListener("message", async (event) => {
  if (event.data.type === MESSAGE_TYPES.FETCH_HTML_DOCUMENT) {
    try {
      const response = await fetch(event.data.url, { signal: abortController.signal });
      if (!response.ok) { throw new Error("Network error: No response from server"); }
      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");
      const chunks = [];
      while (true) {
        const { done, value } = await reader.read();
        if (done) { break; }
        chunks.push(decoder.decode(value, { stream: true }));
      }
      const content = chunks.join("");
      const doc = new DOMParser().parseFromString(content, "text/html");
      self.postMessage({ type: MESSAGE_TYPES.FETCH_HTML_DOCUMENT_RESPONSE, payload: doc });
    } catch (error) {
      if (error.name === "AbortError") { self.postMessage({ type: MESSAGE_TYPES.CANCELLED }); }
      else { self.postMessage({ type: MESSAGE_TYPES.ERROR, message: error.message }); }
    }
  } else if (event.data.type === MESSAGE_TYPES.ABORT) {
    abortController.abort();
    abortController = new AbortController();
    self.postMessage({ type: MESSAGE_TYPES.ABORTED });
  }
});