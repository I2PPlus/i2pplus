/**
 * @module messageTypes
 * @file messageTypes.js - Message protocol constants for the snarkWork Web Worker.
 * @description Message type identifiers used to coordinate fetch and abort requests
 * between the main thread and the snarkWork worker. Each fetch request carries a
 * requestId so multiple requests can be in flight concurrently.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * @constant {Object} MESSAGE_TYPES
 * @description Message types exchanged between the main thread and the snarkWork worker.
 * @property {string} READY - Worker signals readiness after startup.
 * @property {string} FETCH_HTML_DOCUMENT - Main thread requests an HTML document fetch.
 * @property {string} FETCH_HTML_DOCUMENT_RESPONSE - Worker returns the fetched HTML text.
 * @property {string} FETCH_HTML_DOCUMENT_ERROR - Worker reports a fetch failure.
 * @property {string} ABORT - Main thread requests cancellation of an in-flight fetch.
 * @example
 * // Send a fetch request to the worker
 * worker.postMessage({type: MESSAGE_TYPES.FETCH_HTML_DOCUMENT, requestId: 1, url: "/i2psnark/.ajax/xhr1.html"});
 */
const MESSAGE_TYPES = {
  READY: "READY",
  FETCH_HTML_DOCUMENT: "FETCH_HTML_DOCUMENT",
  FETCH_HTML_DOCUMENT_RESPONSE: "FETCH_HTML_DOCUMENT_RESPONSE",
  FETCH_HTML_DOCUMENT_ERROR: "FETCH_HTML_DOCUMENT_ERROR",
  ABORT: "ABORT"
};

export {MESSAGE_TYPES};
