/**
 * @module diffWorker
 * @description A SharedWorker that diffs table row HTML between snapshots.
 * Receives fragment HTML containing a named tbody whose rows carry data-key
 * attributes (emitted by the server in contentonly fragment mode) and posts
 * back only the changed, inserted, and removed rows, so the main thread
 * patches a handful of rows instead of re-parsing and re-diffing the whole
 * table on every refresh tick.
 *
 * Workers cannot parse DOM, so rows are extracted by string scanning with
 * depth counting: profile rows embed a mini table (renderPeerHTML), so a row
 * can contain nested <tr> elements, and scanning to the first </tr> would
 * truncate it. When row order changes, rows lack keys, or there is no
 * snapshot yet, the result is a "full" fallback carrying the whole tbody,
 * which the main thread patches wholesale.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

/** @type {Map<string, {keys: string[], rows: Map<string,string>}>} */
const snapshots = new Map();

/**
 * Extracts the opening tag of a tbody by id.
 * @function findTbodyOpenTag
 * @param {string} html - The HTML to scan
 * @param {string} id - The tbody element id
 * @returns {string|null} The full opening tag, or null
 */
function findTbodyOpenTag(html, id) {
  const re = new RegExp("<tbody[^>]*\\bid=[\"']?" + id + "[\"']?[^>]*>");
  const match = html.match(re);
  return match ? match[0] : null;
}

/**
 * Extracts the inner HTML of a tbody by id.
 * @function extractTbody
 * @param {string} html - The HTML to scan
 * @param {string} id - The tbody element id
 * @returns {string|null} The tbody inner HTML, or null
 */
function extractTbody(html, id) {
  const openTag = findTbodyOpenTag(html, id);
  if (!openTag) { return null; }
  const start = html.indexOf(openTag) + openTag.length;
  const end = html.indexOf("</tbody>", start);
  if (end < 0) { return null; }
  return html.substring(start, end);
}

/**
 * Extracts the full tbody element (opening tag through closing tag) by id,
 * for wholesale replacement by the main thread.
 * @function extractTbodyOuter
 * @param {string} html - The HTML to scan
 * @param {string} id - The tbody element id
 * @returns {string|null} The full tbody element HTML, or null
 */
function extractTbodyOuter(html, id) {
  const openTag = findTbodyOpenTag(html, id);
  if (!openTag) { return null; }
  const start = html.indexOf(openTag);
  const end = html.indexOf("</tbody>", start);
  if (end < 0) { return null; }
  return html.substring(start, end + "</tbody>".length);
}

/**
 * Extracts rows as keyed HTML entries, tracking nesting so rows that embed
 * a table (nested <tr> elements) are captured in full.
 * @function extractRows
 * @param {string} html - The tbody inner HTML
 * @returns {Array<{key: string|null, html: string}>}
 */
function extractRows(html) {
  const rows = [];
  const re = /<tr\b[^>]*>|<\/tr>/g;
  let match;
  let depth = 0;
  let rowStart = -1;
  while ((match = re.exec(html)) !== null) {
    if (match[0].charAt(1) === "/") {
      depth--;
      if (depth === 0 && rowStart >= 0) {
        const rowHtml = html.substring(rowStart, re.lastIndex);
        const openTag = rowHtml.substring(0, rowHtml.indexOf(">") + 1);
        const keyMatch = openTag.match(/data-key=["']([^"']+)["']/);
        rows.push({ key: keyMatch ? keyMatch[1] : null, html: rowHtml });
        rowStart = -1;
      }
    } else {
      depth++;
      if (depth === 1) { rowStart = match.index; }
    }
  }
  return rows;
}

/**
 * Posts a result to the port that sent the request.
 * @function postResult
 * @param {MessagePort} port - The requesting port
 * @param {string} url - The request url
 * @param {Object} payload - The result payload
 * @returns {void}
 */
function postResult(port, url, payload) {
  port.postMessage(Object.assign({ url }, payload));
}

/**
 * Diffs a new row set against the stored snapshot for the url and posts the
 * result: "unchanged" (nothing to patch), "rows" (changed, inserted, and
 * removed rows), or "full" (main thread replaces the whole tbody).
 * @function handleDiff
 * @param {MessagePort} port - The requesting port
 * @param {string} url - The snapshot key
 * @param {string} html - The new fragment HTML
 * @param {string} tbodyId - The tbody element id
 * @returns {void}
 */
function handleDiff(port, url, html, tbodyId) {
  const tbodyHtml = extractTbody(html, tbodyId);
  if (!tbodyHtml) {
    postResult(port, url, { action: "full" });
    return;
  }
  const rows = extractRows(tbodyHtml);
  const keys = rows.map(r => r.key);
  if (keys.some(k => k === null)) {
    postResult(port, url, { action: "full", tbodyHtml: extractTbodyOuter(html, tbodyId) });
    return;
  }
  const newRows = new Map(rows.map(r => [r.key, r.html]));
  const snapshot = snapshots.get(url);
  if (!snapshot) {
    snapshots.set(url, { keys, rows: newRows });
    postResult(port, url, { action: "full", tbodyHtml: extractTbodyOuter(html, tbodyId) });
    return;
  }

  const oldKeys = snapshot.keys;
  const oldRows = snapshot.rows;
  const newKeySet = new Set(keys);
  const oldKeySet = new Set(oldKeys);

  const removed = oldKeys.filter(k => !newKeySet.has(k));
  const inserted = keys.filter(k => !oldKeySet.has(k));

  // Order check: surviving keys must keep their relative order, otherwise
  // the main thread cannot patch incrementally and gets the full fallback.
  const survivorOrder = oldKeys.filter(k => newKeySet.has(k));
  const survivorIndex = new Map(keys.map((k, i) => [k, i]));
  let lastIndex = -1;
  let orderChanged = false;
  for (const k of survivorOrder) {
    const index = survivorIndex.get(k);
    if (index < lastIndex) { orderChanged = true; break; }
    lastIndex = index;
  }

  snapshots.set(url, { keys, rows: newRows });
  if (orderChanged) {
    postResult(port, url, { action: "full", tbodyHtml: extractTbodyOuter(html, tbodyId) });
    return;
  }

  const changed = [];
  for (const k of survivorOrder) {
    const newHtml = newRows.get(k);
    if (newHtml !== oldRows.get(k)) { changed.push(newHtml); }
  }
  const inserts = inserted.map(k => ({
    key: k,
    html: newRows.get(k),
    before: keys[keys.indexOf(k) + 1] || null
  }));

  if (changed.length === 0 && inserts.length === 0 && removed.length === 0) {
    postResult(port, url, { action: "unchanged" });
    return;
  }
  postResult(port, url, { action: "rows", rows: changed, inserts, removed });
}

/**
 * Handles new SharedWorker connections.
 * @function self.onconnect
 * @param {MessageEvent} e - The connection event containing ports
 * @returns {void}
 */
self.onconnect = function(e) {
  const port = e.ports[0];
  port.onmessage = function(event) {
    const { url, html, tbodyId } = event.data;
    if (!url || !html || !tbodyId) { return; }
    handleDiff(port, url, html, tbodyId);
  };
};
