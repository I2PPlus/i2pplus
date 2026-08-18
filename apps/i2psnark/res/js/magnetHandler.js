/**
 * @module magnetHandler
 * @file magnetHandler.js - Handle the magnet link handler page.
 * @description The browser opens this page when a magnet: link is handed to the
 * I2PSnark Bridge extension (or the fallback handler registration). The magnet
 * arrives in the URL as the nofilter_magnet query parameter (the nofilter_ prefix
 * keeps the router's XSS filter from stripping the '&' separators every real
 * magnet link contains). This script POSTs it to the browser API /_add on the
 * same origin, displays the result inline, and dispatches a CustomEvent so the
 * extension can show a browser notification.
 * @author dr|z3d
 * @license AGPL3 or later
 */

"use strict";

const MAGNET_RESULT_EVENT = "i2psnark:magnet-result";

(function () {
    const status = document.getElementById("status");
    const params = new URLSearchParams(location.search);
    const magnet = params.get("nofilter_magnet") || params.get("magnet");

    if (!magnet) {
        if (status) { status.textContent = "No magnet link in URL."; }
        return;
    }

    function showResult(result) {
        if (status && result && result.message) {
            status.textContent = result.message;
        }
        window.dispatchEvent(new CustomEvent(MAGNET_RESULT_EVENT, { detail: result }));
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

    // The protocol handler opens a new tab; close it once we are done when possible.
    setTimeout(function () { window.close(); }, 6000);
})();