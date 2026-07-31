/**
 * @module refreshPayload
 * @file refreshPayload.js - Extracts refresh data from an I2PSnark AJAX response.
 * @description Parses a fetched HTML document into a structured payload of container
 * strings and cell texts, so the main thread can update the page without parsing the
 * whole document. Also extracts the snark tunnel counts from the raw tunnel
 * configuration page text. Runs inside the snarkWork worker, on the main-thread
 * fallback path, and in tests.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * @function extractRefreshPayload
 * @description Extracts the containers the refresh pipeline updates: torrent table
 * rows, filter badge, pagination, torrent form, screen log, header/footer cells, and
 * file-listing cells. All values are strings or arrays of strings so the payload can
 * cross the worker postMessage boundary.
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
 * @returns {string[]} returns.headerTH - Inner HTML of each #snarkHead th cell.
 * @returns {string[]} returns.footerTH - Inner HTML of each #snarkFoot th cell.
 * @returns {string[]} returns.fileTds - Inner HTML of each incomplete-file cell.
 * @returns {string[]} returns.fileStats - Inner HTML of each #torrentInfoStats .nowrap cell.
 */
function extractRefreshPayload(doc) {
    const tbody = doc.querySelector("#snarkTbody");
    const activeBadge = doc.querySelector("#filterBar .filter#all.enabled .badge");
    const pagenavtop = doc.querySelector("#pagenavtop");
    const torrentlist = doc.querySelector("#torrentlist");
    const mainsection = doc.querySelector("#mainsection");
    const messages = doc.getElementById("messages");
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
        headerTH: header ? [...header.querySelectorAll("th")].map(th => th.innerHTML) : [],
        footerTH: footer ? [...footer.querySelectorAll("th")].map(th => th.innerHTML) : [],
        fileTds: [...doc.querySelectorAll("#dirInfo tbody tr.incomplete td")].map(td => td.innerHTML),
        fileStats: [...doc.querySelectorAll("#torrentInfoStats .nowrap")].map(el => el.innerHTML)
    };
}

/**
 * @function extractTunnelCounts
 * @description Extracts the snark inbound and outbound tunnel counts from the raw tunnel
 * configuration page text, matching the <span id=snarkIn> and <span id=snarkOut> elements
 * rendered by ConfigTunnelsHelper. Avoids parsing the full page into a DOM.
 * @param {string} text - The raw response text of the tunnel configuration page.
 * @returns {?Object} An object with inCount and outCount string properties, or null when
 * either count is missing.
 */
function extractTunnelCounts(text) {
    const inMatch = text.match(/id\s*=\s*["']?snarkIn(?![-\w])["']?[^>]*>([^<]+)</);
    const outMatch = text.match(/id\s*=\s*["']?snarkOut(?![-\w])["']?[^>]*>([^<]+)</);
    const inCount = inMatch ? inMatch[1].trim() : "";
    const outCount = outMatch ? outMatch[1].trim() : "";
    if (inCount && outCount) {return {inCount, outCount};}
    return null;
}

export {extractRefreshPayload, extractTunnelCounts};
