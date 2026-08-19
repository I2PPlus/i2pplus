/**
 * @module i2psnarkBridgeContent
 * @file content.js - I2PSnark Bridge content script.
 * @description The router's /i2psnark/magnet handler page POSTs the magnet to the
 * browser API itself (same origin, so the router's origin check passes) and
 * dispatches a CustomEvent with the result. This script (injected at
 * document_start, so the listener is attached before any page script runs)
 * forwards the result to the background event page, which shows a browser
 * notification. The page works standalone without the extension; the
 * notification is the only thing the extension adds.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function () {
  "use strict";

  const CONTEXT_PREFIX = "/i2psnark";
  const MAGNET_RESULT_EVENT = "i2psnark:magnet-result";

  window.addEventListener(MAGNET_RESULT_EVENT, function (ev) {
    var result = ev.detail;
    if (!result) {
      return;
    }
    chrome.runtime.sendMessage({ type: "magnetResult", result: result });
  });

  // Origin discovery: remember the base URL of the I2PSnark instance that
  // served this page so magnet handoffs can be routed there even when the
  // extension's manifest uriTemplate default (router port 7657) does not
  // match the instance actually running (standalone 8002, custom console
  // port, https). The server emits the authoritative value as a meta tag in
  // the magnet handler page head; it is only visible once the document has
  // parsed, so discovery runs on DOMContentLoaded. Fall back to the location
  // origin + context path for pages served without the meta.
  function isSnarkPath(path) {
    return path === CONTEXT_PREFIX || path.startsWith(CONTEXT_PREFIX + "/");
  }

  function discoverBaseUrl() {
    if (!isSnarkPath(window.location.pathname)) {
      return;
    }
    const meta = document.querySelector('meta[name="i2psnark-base-url"]');
    let base = meta && meta.content ? meta.content.trim() : "";
    if (!base) {
      base = window.location.origin + CONTEXT_PREFIX;
    }
    let u;
    try {
      u = new URL(base);
    } catch (e) {
      return;
    }
    if (u.protocol !== "http:" && u.protocol !== "https:") {
      return;
    }
    if (u.hostname !== "127.0.0.1" && u.hostname !== "localhost") {
      return;
    }
    base = u.origin + u.pathname.replace(/\/+$/, "");
    chrome.storage.local.get("baseUrl", function (stored) {
      if (stored.baseUrl !== base) {
        chrome.storage.local.set({ baseUrl: base });
        chrome.runtime.sendMessage({ type: "discoverBaseUrl", baseUrl: base });
      }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", discoverBaseUrl);
  } else {
    discoverBaseUrl();
  }
})();
