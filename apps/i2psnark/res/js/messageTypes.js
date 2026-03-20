/**
 * @file messageTypes.js - Defines message type constants for Web Worker communication in I2PSnark.
 * @description Provides a shared set of message type identifiers used to coordinate
 * fetch, abort, and error responses between the main thread and the snarkWork Web Worker.
 */

/**
 * @constant {Object} MESSAGE_TYPES - Enumeration of message types for worker communication.
 * @property {string} FETCH_HTML_DOCUMENT - Request to fetch an HTML document via the worker.
 * @property {string} FETCH_HTML_DOCUMENT_RESPONSE - Response containing the fetched HTML document.
 * @property {string} CANCELLED - Indicates a fetch operation was cancelled (AbortError).
 * @property {string} ERROR - Indicates an error occurred during a fetch operation.
 * @property {string} ABORT - Request to abort an in-progress fetch operation.
 * @property {string} ABORTED - Confirmation that an abort request was processed.
 * @example
 * // Send a fetch request to the worker
 * worker.postMessage({ type: MESSAGE_TYPES.FETCH_HTML_DOCUMENT, url: '/i2psnark/.ajax/xhr1.html' });
 */
const MESSAGE_TYPES = {
  FETCH_HTML_DOCUMENT: "FETCH_HTML_DOCUMENT",
  FETCH_HTML_DOCUMENT_RESPONSE: "FETCH_HTML_DOCUMENT_RESPONSE",
  CANCELLED: "CANCELLED",
  ERROR: "ERROR",
  ABORT: "ABORT",
  ABORTED: "ABORTED"
};

export {MESSAGE_TYPES};