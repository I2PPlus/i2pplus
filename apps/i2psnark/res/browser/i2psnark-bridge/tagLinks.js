/**
 * @module i2psnarkBridgeTagLinks
 * @file tagLinks.js - I2PSnark Bridge link tagger and .torrent click handler.
 * @description Tags magnet: links with target="_blank" so the protocol handler
 * page Firefox opens never replaces the page the user is on (with
 * target="_blank" the handler always lands in a fresh tab, and the handler page
 * magnetHandler.js closes that tab the moment it is done). When enabled in the
 * options page, clicks on .torrent links are intercepted and sent to the
 * background script, which asks the router to fetch and add the torrent; the
 * .i2p-only option restricts that handling to hosts ending in .i2p, otherwise
 * the link behaves normally.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function () {
  "use strict";

  const MAGNET_SELECTOR = 'a[href^="magnet:"]';
  const TORRENT_SELECTOR = 'a[href$=".torrent"]';
  const PREFS_DEFAULTS = { handleTorrentLinks: false, i2pOnly: true };

  function isI2pUrl(url) {
    try {
      return /\.i2p$/i.test(new URL(url).hostname);
    } catch (e) {
      return false;
    }
  }

  function tag(root) {
    if (!root || root.nodeType !== 1) {
      return;
    }
    const links = root.matches(MAGNET_SELECTOR) ? [root] : root.querySelectorAll(MAGNET_SELECTOR);
    for (const a of links) {
      if (!a.target) {
        a.target = "_blank";
      }
    }
  }

  document.addEventListener("click", function (event) {
    const link = event.target && event.target.closest ? event.target.closest(TORRENT_SELECTOR) : null;
    if (!link) {return;}
    chrome.storage.local.get(PREFS_DEFAULTS, function (prefs) {
      if (!prefs.handleTorrentLinks) {return;}
      if (prefs.i2pOnly && !isI2pUrl(link.href)) {return;}
      event.preventDefault();
      chrome.runtime.sendMessage({ type: "addTorrent", url: link.href });
    });
  });

  tag(document);

  new MutationObserver(function (mutations) {
    for (const m of mutations) {
      for (const n of m.addedNodes) {
        if (n.nodeType === 1) {
          tag(n);
        }
      }
    }
  }).observe(document, { childList: true, subtree: true });
})();