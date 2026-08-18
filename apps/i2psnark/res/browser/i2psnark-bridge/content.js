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
  window.addEventListener("i2psnark:magnet-result", function (ev) {
    var result = ev.detail;
    if (!result) {
      return;
    }
    chrome.runtime.sendMessage({ type: "magnetResult", result: result });
  });
})();