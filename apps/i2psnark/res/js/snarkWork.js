/**
 * @module snarkWork
 * @file snarkWork.js - Web Worker for I2PSnark update requests.
 * @description Fetches AJAX HTML documents off the main thread with streaming reads and
 * per-request abort support. Parses the response and extracts the refresh payload so
 * the main thread only performs minimal DOM writes. Document objects cannot cross the
 * worker boundary, so all payload values are strings or arrays of strings.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {MESSAGE_TYPES} from "./messageTypes.js";
import {extractRefreshPayload} from "./refreshPayload.js";

/**
 * @type {Map<number, AbortController>}
 * @description Per-request controllers keyed by requestId, used to cancel in-flight fetches.
 */
const activeRequests = new Map();

/**
 * @async
 * @function handleFetch
 * @description Fetches the URL, streaming the response body into a string, parses it,
 * and posts the extracted refresh payload back to the main thread. Aborted fetches are
 * silent (the main thread initiated the cancel); other failures are reported as
 * FETCH_HTML_DOCUMENT_ERROR.
 * @param {number} requestId - Correlates the response with the original request.
 * @param {string} url - The URL to fetch.
 * @returns {Promise<void>}
 */
async function handleFetch(requestId, url) {
    const controller = new AbortController();
    activeRequests.set(requestId, controller);
    try {
        const response = await fetch(url, {signal: controller.signal});
        if (!response.ok) {throw new Error("HTTP " + response.status);}
        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");
        const chunks = [];
        while (true) {
            const {done, value} = await reader.read();
            if (done) {break;}
            chunks.push(decoder.decode(value, {stream: true}));
        }
        chunks.push(decoder.decode());
        const doc = new DOMParser().parseFromString(chunks.join(""), "text/html");
        self.postMessage({
            type: MESSAGE_TYPES.FETCH_HTML_DOCUMENT_RESPONSE,
            requestId: requestId,
            url: url,
            payload: extractRefreshPayload(doc)
        });
    } catch (error) {
        if (error.name !== "AbortError") {
            self.postMessage({
                type: MESSAGE_TYPES.FETCH_HTML_DOCUMENT_ERROR,
                requestId: requestId,
                url: url,
                message: error.message
            });
        }
    } finally {
        activeRequests.delete(requestId);
    }
}

/**
 * @description Handles FETCH_HTML_DOCUMENT and ABORT messages from the main thread.
 * @param {MessageEvent} event - The message event.
 * @returns {void}
 */
self.addEventListener("message", (event) => {
    const {type, requestId, url} = event.data || {};
    if (type === MESSAGE_TYPES.FETCH_HTML_DOCUMENT && url) {
        handleFetch(requestId, url);
    } else if (type === MESSAGE_TYPES.ABORT) {
        const controller = activeRequests.get(requestId);
        if (controller) {controller.abort();}
    }
});

self.postMessage({type: MESSAGE_TYPES.READY});
