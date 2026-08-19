/**
 * @module i2psnarkBridgeBackground
 * @file background.js - I2PSnark Bridge background event page.
 * @description The /i2psnark/magnet handler page POSTs magnet URIs to the local
 * I2PSnark browser API itself (POST /_add, nofilter_newURL so the router's XSS
 * filter does not strip the '&' separators). The content script forwards the
 * page's result event here, and this script shows a browser notification with
 * the result. This page also adds an X-I2PSnark-Bridge version header to every
 * request to the router so the router can tell whether the installed extension
 * is current and offer an update on the config page. The manifest's protocol
 * handler points magnet: URIs at the router default (127.0.0.1:7657); because
 * the XPI is static, that default cannot match the instance the extension was
 * installed from when it runs elsewhere (standalone on 8002, custom console
 * port, https). The content script therefore discovers the real base URL and
 * stores it here, and this page redirects /i2psnark/magnet requests to that
 * base (a user-pinned override from the options page wins over discovery).
 * Notification behavior is controlled by the settings on the options page
 * (chrome.storage.local).
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function () {
  "use strict";

  const BRIDGE_TARGETS = ["http://127.0.0.1/*", "http://localhost/*"];
  const CONTEXT_PATH = "/i2psnark";
  const MAGNET_PATH = CONTEXT_PATH + "/magnet";
  const SETTINGS_DEFAULTS = {
    notifySuccess: true,
    notifyFailure: true,
    baseUrl: null,
    overrideBaseUrl: ""
  };

  let settings = SETTINGS_DEFAULTS;
  let baseUrl = null;        // auto-discovered instance base URL
  let overrideBaseUrl = "";  // user-pinned via the options page

  chrome.storage.local.get(SETTINGS_DEFAULTS, (stored) => {
    settings = Object.assign({}, SETTINGS_DEFAULTS, stored);
    baseUrl = stored.baseUrl || null;
    overrideBaseUrl = stored.overrideBaseUrl || "";
  });

  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "local") {return;}
    for (const key of Object.keys(changes)) {
      settings[key] = changes[key].newValue;
    }
    if ("baseUrl" in changes) {baseUrl = changes.baseUrl.newValue || null;}
    if ("overrideBaseUrl" in changes) {overrideBaseUrl = changes.overrideBaseUrl || "";}
  });

  function normalizeBase(raw) {
    if (!raw) {return null;}
    let u;
    try {
      u = new URL(raw);
    } catch (e) {
      return null;
    }
    if (u.protocol !== "http:" && u.protocol !== "https:") {return null;}
    let path = u.pathname;
    if (path.endsWith(MAGNET_PATH)) {path = path.slice(0, -MAGNET_PATH.length);}
    path = path.replace(/\/+$/, "");
    if (!path) {path = CONTEXT_PATH;}
    return u.origin + path;
  }

  function effectiveBaseUrl() {
    const pinned = normalizeBase(overrideBaseUrl);
    if (pinned) {return pinned;}
    return normalizeBase(baseUrl);
  }

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

  chrome.webRequest.onBeforeRequest.addListener(
    function (details) {
      let u;
      try {
        u = new URL(details.url);
      } catch (e) {
        return {};
      }
      if (u.pathname !== MAGNET_PATH) {return {};}
      const target = effectiveBaseUrl();
      if (!target) {return {};}
      const redirect = target + "/magnet" + (u.search || "");
      if (redirect === details.url) {return {};}
      return { redirectUrl: redirect };
    },
    { urls: BRIDGE_TARGETS },
    ["blocking"]
  );

  chrome.runtime.onMessage.addListener(function (msg, sender, sendResponse) {
    if (!msg) {return;}
    if (msg.type === "magnetResult") {
      notify(msg.result);
    }
    if (msg.type === "discoverBaseUrl" && msg.baseUrl) {
      const norm = normalizeBase(msg.baseUrl);
      if (norm && baseUrl !== norm) {
        baseUrl = norm;
        chrome.storage.local.set({ baseUrl: norm });
      }
    }
  });

  chrome.runtime.onInstalled.addListener(function (details) {
    if (details.reason !== "install") {return;}
    // The user installed this extension from an I2PSnark page; reload that
    // tab so the content script can discover the origin that served it
    // (standalone 8002, custom console port, https) before the first magnet
    // click. The reload is the one visible side effect; the install dialog
    // and flow are unchanged.
    chrome.tabs.query({ url: BRIDGE_TARGETS }, function (tabs) {
      for (const t of tabs) {
        let u;
        try {
          u = new URL(t.url);
        } catch (e) {
          continue;
        }
        if (u.pathname === CONTEXT_PATH || u.pathname.startsWith(CONTEXT_PATH + "/")) {
          // Skip the magnet handler page (closes itself) and resource paths
          // (the .xpi download tab would re-prompt the install on reload).
          if (u.pathname === MAGNET_PATH || u.pathname.includes("/.res/")) {continue;}
          chrome.tabs.reload(t.id);
        }
      }
    });
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
