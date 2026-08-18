/**
 * @module i2psnarkBridgeBackground
 * @file background.js - I2PSnark Bridge background event page.
 * @description The /i2psnark/magnet handler page POSTs magnet URIs to the local
 * I2PSnark browser API itself (POST /_add, nofilter_newURL so the router's XSS
 * filter does not strip the '&' separators). The content script forwards the
 * page's result event here, and this script shows a browser notification with
 * the result. This page also adds an X-I2PSnark-Bridge version header to every
 * request to the router so the router can tell whether the installed extension
 * is current and offer an update on the config page. Notification behavior is
 * controlled by the settings on the options page (chrome.storage.local).
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function () {
  "use strict";

  const BRIDGE_TARGETS = ["http://127.0.0.1/*", "http://localhost/*"];
  const SETTINGS_DEFAULTS = {
    notifySuccess: true,
    notifyFailure: true
  };

  let settings = SETTINGS_DEFAULTS;

  chrome.storage.local.get(SETTINGS_DEFAULTS, (stored) => {
    settings = Object.assign({}, SETTINGS_DEFAULTS, stored);
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "local") {return;}
    for (const key of Object.keys(changes)) {
      settings[key] = changes[key].newValue;
    }
  });

  chrome.webRequest.onBeforeSendHeaders.addListener(
    function (details) {
      details.requestHeaders.push({
        name: "X-I2PSnark-Bridge",
        value: chrome.runtime.getManifest().version
      });
      return { requestHeaders: details.requestHeaders };
    },
    { urls: BRIDGE_TARGETS },
    ["blocking", "requestHeaders"]
  );

  chrome.runtime.onMessage.addListener(function (msg, sender, sendResponse) {
    if (!msg) {return;}
    if (msg.type === "magnetResult") {
      notify(msg.result);
    }
  });

  function notify(result) {
    var ok = !!(result && result.ok);
    if (ok && !settings.notifySuccess) {return;}
    if (!ok && !settings.notifyFailure) {return;}
    var title = ok ? "Added to I2PSnark" : "I2PSnark add failed";
    var message = "";
    if (result) {
      if (ok) {
        var rest = result.message.substring(3).trim();
        message = rest || "Magnet added";
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
