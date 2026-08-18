/**
 * @module magnetHandler
 * @file magnetHandler.js - Handle the magnet link handler page.
 * @description The browser opens this page in a new tab when a magnet: link is
 * handed to the I2PSnark Bridge extension (registered via protocol_handlers).
 * The magnet arrives in the URL as the nofilter_magnet query parameter (the
 * nofilter_ prefix keeps the router's XSS filter from stripping the '&'
 * separators every real magnet link contains). This script POSTs it to the
 * browser API /_add on the same origin and dispatches a CustomEvent so the
 * extension can show a browser notification. The page body is blank and the
 * tab closes itself the moment the POST completes, so it is never noticed.
 * @author dr|z3d
 * @license AGPL3 or later
 */

"use strict";

const MAGNET_RESULT_EVENT = "i2psnark:magnet-result";

(function () {
    const status = document.getElementById("status");
    const params = new URLSearchParams(location.search);
    const magnet = params.get("nofilter_magnet") || params.get("magnet");

    function closeNow() {
        // The tab only exists to hand the magnet to the browser API. Firefox may
        // open the protocol-handler URI in the current tab, replacing the page
        // we were on; if so, navigate back to it. If the handler opened in its
        // own tab, close the tab instead.
        if (history.length > 1) {
            history.back();
        } else {
            try { window.close(); } catch (e) {}
        }
    }

    if (!magnet) {
        closeNow();
        return;
    }

    function showResult(result) {
        if (status && result && result.message) {
            status.textContent = result.message;
        }
        window.dispatchEvent(new CustomEvent(MAGNET_RESULT_EVENT, { detail: result }));
        // Tell any open I2PSnark page to refresh immediately: the torrent was
        // added (or failed) without a form submit, so the main page would
        // otherwise only hear about it on the next interval tick.
        try {
            new BroadcastChannel("i2psnark:refresh").postMessage("result");
        } catch (e) {}
        // Give the extension's content script a moment to forward the event to
        // the background page (chrome.runtime.sendMessage is async; closing the
        // tab in the same tick could drop the notification), then close.
        setTimeout(closeNow, 150);
    }

    const body = new URLSearchParams();
    body.set("nofilter_newURL", magnet);
    fetch("_add", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" },
        body: body.toString()
    }).then(function (resp) {
        return resp.text();
    }).then(function (text) {
        const message = (text || "HTTP error").trim();
        showResult({ ok: message.startsWith("OK"), message: message });
    }).catch(function (err) {
        showResult({ ok: false, message: "ERR: " + err.message });
    });

    // Safety net: if the fetch hangs, close the tab anyway.
    setTimeout(closeNow, 1500);
})();
