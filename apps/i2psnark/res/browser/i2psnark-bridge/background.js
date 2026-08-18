/**
 * @module i2psnarkBridgeBackground
 * @file background.js - I2PSnark Bridge background event page.
 * @description The /i2psnark/magnet handler page POSTs magnet URIs to the local
 * I2PSnark browser API itself (POST /_add, nofilter_newURL so the router's XSS
 * filter does not strip the '&' separators). The content script forwards the
 * page's result event here, and this script shows a browser notification with
 * the result.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function () {
  "use strict";

  chrome.runtime.onMessage.addListener(function (msg, sender, sendResponse) {
    if (!msg || msg.type !== "magnetResult") {
      return;
    }
    notify(msg.result);
  });

  function notify(result) {
    var ok = !!(result && result.ok);
    var title = ok ? "Added to I2PSnark" : "I2PSnark add failed";
    var message = "";
    if (result) {
      if (ok) {
        var rest = result.message.substring(3).trim();
        message = rest || "Torrent added";
      } else {
        message = result.message;
      }
    }
    chrome.notifications.create({
      type: "basic",
      iconUrl: chrome.runtime.getURL("icons/favicon-128.png"),
      title: title,
      message: message
    });
  }
})();