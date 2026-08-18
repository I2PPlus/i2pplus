/**
 * @module i2psnarkBridgeTagLinks
 * @file tagLinks.js - I2PSnark Bridge magnet link tagger.
 * @description Tags magnet: links with target="_blank" so the protocol handler
 * page Firefox opens never replaces the page the user is on (with
 * target="_blank" the handler always lands in a fresh tab, and the handler page
 * magnetHandler.js closes that tab the moment it is done). When the .i2p-only
 * option is enabled, magnet links on pages outside .i2p are ignored entirely:
 * the click is prevented, so the registered protocol handler never opens and
 * nothing about them is sent to the router.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function () {
  "use strict";

  const MAGNET_SELECTOR = 'a[href^="magnet:"]';
  const PREFS_DEFAULTS = { i2pOnly: true };

  let prefs = PREFS_DEFAULTS;

  chrome.storage.local.get(PREFS_DEFAULTS, (stored) => {
    prefs = Object.assign({}, PREFS_DEFAULTS, stored);
  });
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== "local") {return;}
    for (const key of Object.keys(changes)) {
      if (key in prefs) {
        prefs[key] = changes[key].newValue;
      }
    }
  });

  function isI2pPage() {
    try {
      return /\.i2p$/i.test(window.location.hostname);
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
    const magnet = event.target && event.target.closest ? event.target.closest(MAGNET_SELECTOR) : null;
    if (!magnet) {return;}
    if (prefs.i2pOnly && !isI2pPage()) {
      // The browser would otherwise open the registered magnet handler page.
      // preventDefault must run synchronously within the event dispatch, so
      // the prefs are cached above, not fetched here.
      event.preventDefault();
      return;
    }
    // Enforce target="_blank" at click time too: pages may add or rewrite
    // magnet links after initial tagging (dynamic hrefs, fast clicks), and
    // without the new-tab behavior the handler page would replace the page
    // the user is on. Setting the target during the click affects the
    // default navigation, which only runs after the event dispatch completes.
    magnet.target = "_blank";
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