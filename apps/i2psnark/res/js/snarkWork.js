/* I2P+ I2PSnark snarkWork.js by dr|z3d */
/* Web worker for network requests */
/* License: AGPL3 or later */

const MESSAGE_TYPES = {
  FETCH_HTML_DOCUMENT: "FETCH_HTML_DOCUMENT",
  FETCH_HTML_DOCUMENT_RESPONSE: "FETCH_HTML_DOCUMENT_RESPONSE",
  CANCELLED: "CANCELLED",
  ERROR: "ERROR",
  ABORT: "ABORT",
  ABORTED: "ABORTED"
};

let abortController = new AbortController();

self.addEventListener("message", async (event) => {
  if (event.data.type === MESSAGE_TYPES.FETCH_HTML_DOCUMENT) {
    try {
      const response = await fetch(event.data.url, { signal: abortController.signal });
      if (!response.ok) throw new Error("Network error: No response from server");
      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");
      let content = "";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        content += decoder.decode(value, { stream: true });
      }
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