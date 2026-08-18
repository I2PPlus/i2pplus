/**
 * @module i2psnarkBridgeTagLinks
 * @file tagLinks.js - I2PSnark Bridge link tagger.
 * @description Force magnet: and .torrent links to open in a new tab so the
 * protocol handler page Firefox opens never replaces the page the user is on.
 * Firefox may otherwise navigate the current tab to the handler URI; with
 * target="_blank" the handler always lands in a fresh tab, and the handler
 * page (magnetHandler.js) closes that tab the moment it is done.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function () {
  "use strict";

  const SELECTOR = 'a[href^="magnet:"], a[href$=".torrent"]';

  function tag(root) {
    if (!root || root.nodeType !== 1) {
      return;
    }
    const links = root.matches(SELECTOR) ? [root] : root.querySelectorAll(SELECTOR);
    for (const a of links) {
      if (!a.target) {
        a.target = "_blank";
      }
    }
  }

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
