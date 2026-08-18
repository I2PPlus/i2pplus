/**
 * @module i2psnarkBridgeBackground
 * @file background.js - I2PSnark Bridge background event page.
 * @description The /i2psnark/magnet handler page POSTs magnet URIs to the local
 * I2PSnark browser API itself (POST /_add, nofilter_newURL so the router's XSS
 * filter does not strip the '&' separators). The content script forwards the
 * page's result event here, and this script shows a browser notification with
 * the result. This page also adds an X-I2PSnark-Bridge version header to every
 * request to the router so the router can tell whether the installed extension
 * is current and offer an update on the config page. It handles .torrent link
 * clicks forwarded by tagLinks.js by asking the router to fetch and add the
 * torrent. Notification and .torrent handling behavior is controlled by the
 * settings on the options page (chrome.storage.local).
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function () {
  "use strict";

  const BRIDGE_TARGETS = ["http://127.0.0.1/*", "http://localhost/*"];
  const ROUTER_ADD_URL = "http://127.0.0.1:7657/i2psnark/_add";
  const SETTINGS_DEFAULTS = {
    notifySuccess: true,
    notifyFailure: true,
    handleTorrentLinks: false,
    i2pOnly: true
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
    } else if (msg.type === "addTorrent") {
      addTorrent(msg.url);
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

  function isI2pUrl(url) {
    try {
      return /\.i2p$/i.test(new URL(url).hostname);
    } catch (e) {
      return false;
    }
  }

  function addTorrent(url) {
    if (!url || (settings.i2pOnly && !isI2pUrl(url))) {return;}
    const body = new URLSearchParams();
    body.set("nofilter_newURL", url);
    fetch(ROUTER_ADD_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" },
      body: body.toString()
    }).then(function (resp) {
      return resp.text();
    }).then(function (text) {
      const message = (text || "HTTP error").trim();
      notify({ ok: message.startsWith("OK"), message: message });
    }).catch(function (err) {
      notify({ ok: false, message: "ERR: " + err.message });
    });
  }
})();
