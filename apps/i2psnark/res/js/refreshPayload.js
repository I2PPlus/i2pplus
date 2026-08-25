/**
 * @module refreshPayload
 * @file refreshPayload.js - Extracts refresh data from an I2PSnark AJAX response.
 * @description Parses a fetched HTML document into a structured payload of container
 * strings and cell texts, so the main thread can update the page without parsing the
 * whole document. Runs inside the snarkWork worker, on the main-thread
 * fallback path, and in tests.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * @function extractRefreshPayload
 * @description Extracts the containers the refresh pipeline updates: torrent table
 * rows, filter badge, pagination, torrent form, screen log, header/footer cells, and
 * file-listing cells. All values are strings or arrays of strings so the payload can
 * cross the worker postMessage boundary. Extraction happens wherever this module runs
 * (worker or main thread), so downstream code never parses response HTML again.
 * @param {Document} doc - The parsed AJAX response document.
 * @returns {Object} The refresh payload.
 * @returns {?string} returns.snarkTbody - Inner HTML of the #snarkTbody table body.
 * @returns {string[]} returns.dhtDebug - Outer HTML of each #dhtDebug .dht row.
 * @returns {?string} returns.badgeText - Text of the enabled all-filter badge, if any.
 * @returns {boolean} returns.filterBarPresent - Whether the response contains #filterBar.
 * @returns {?string} returns.pagenavtop - Outer HTML of the #pagenavtop element.
 * @returns {?string} returns.torrentlist - Inner HTML of the #torrentlist form.
 * @returns {?string} returns.mainsection - Inner HTML of the #mainsection element.
 * @returns {?string} returns.messages - Inner HTML of the #messages screen log.
 * @returns {?string} returns.screenLogStamp - Server-side change stamp for the screen
 *   log ("lastMessageId:messageCount"), or null when the response carries none.
 * @returns {?string} returns.nonce - Value of the form's hidden CSRF nonce input,
 *   or null when the response carries no form.
 * @returns {string[]} returns.headerTH - Inner HTML of each #snarkHead th cell.
 * @returns {string[]} returns.footerTH - Inner HTML of each #snarkFoot th cell.
 * @returns {string[]} returns.fileTds - Trimmed inner HTML of each incomplete-file cell.
 * @returns {string[]} returns.fileStats - Trimmed inner HTML of each #torrentInfoStats .nowrap cell.
 */
function extractRefreshPayload(doc) {
    const tbody = doc.querySelector("#snarkTbody");
    const activeBadge = doc.querySelector("#filterBar .filter#all .badge");
    const pagenavtop = doc.querySelector("#pagenavtop");
    const torrentlist = doc.querySelector("#torrentlist");
    const mainsection = doc.querySelector("#mainsection");
    const messages = doc.getElementById("messages");
    const screenLogStampEl = doc.getElementById("screenlogStamp");
    const nonceInput = doc.querySelector("#torrentlist input[name=nonce]");
    const header = doc.querySelector("#snarkHead");
    const footer = doc.querySelector("#snarkFoot");
    return {
        snarkTbody: tbody ? tbody.innerHTML : null,
        dhtDebug: [...doc.querySelectorAll("#dhtDebug .dht")].map(el => el.outerHTML),
        badgeText: activeBadge ? activeBadge.textContent : null,
        filterBarPresent: !!doc.querySelector("#filterBar"),
        pagenavtop: pagenavtop ? pagenavtop.outerHTML : null,
        torrentlist: torrentlist ? torrentlist.innerHTML : null,
        mainsection: mainsection ? mainsection.innerHTML : null,
        messages: messages ? messages.innerHTML : null,
        screenLogStamp: screenLogStampEl ? screenLogStampEl.getAttribute("data-v") : null,
        nonce: nonceInput ? nonceInput.value : null,
        headerTH: header ? [...header.querySelectorAll("th")].map(th => th.innerHTML) : [],
        footerTH: footer ? [...footer.querySelectorAll("th")].map(th => th.innerHTML) : [],
        fileTds: [...doc.querySelectorAll("#dirInfo tbody tr.incomplete td")].map(td => td.innerHTML.trim()),
        fileStats: [...doc.querySelectorAll("#torrentInfoStats .nowrap")].map(el => el.innerHTML.trim())
    };
}

export {extractRefreshPayload};
