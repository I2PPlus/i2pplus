/**
 * @module diffWorker
 * @description A SharedWorker that parses fragment HTML to a serializable
 * VDOM tree (vdomParser.js) and diffs table rows between snapshots.
 *
 * Receives fragment HTML containing a named tbody whose rows carry data-key
 * attributes (emitted by the server in contentonly fragment mode) and posts
 * back only the changed, inserted, and removed rows, so the main thread
 * patches a handful of rows instead of re-parsing and re-diffing the whole
 * table on every refresh tick.
 *
 * Parsing happens entirely in this worker: vdomParser.js produces a plain
 * data tree ({tagName, attributes, children} / {nodeName, nodeValue}) that
 * survives structured clone, and the main thread realizes only the rows it
 * receives. Row extraction is a tree walk, so nested tables inside a row
 * (renderPeerHTML) need no string depth counting. When row order changes,
 * rows lack keys, or there is no snapshot yet, the result is a "full"
 * fallback carrying the whole tbody VDOM, which the main thread patches
 * wholesale.
 *
 * Without a tbodyId the worker acts as a pure parser and posts the fragment
 * VDOM back ("parsed" action), so the main thread never calls DOMParser.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

importScripts("vdomParser.js");

/** @type {Map<string, {keys: string[], rows: Map<string,string>}>} */
const snapshots = new Map();

/**
 * Finds the first tbody element with the given id in a VDOM tree.
 * @function findTbody
 * @param {Object} root - The parsed VDOM root
 * @param {string} id - The tbody element id
 * @returns {Object|null} The tbody node, or null
 */
function findTbody(root, id) {
  const stack = [root];
  while (stack.length > 0) {
    const node = stack.pop();
    if (node.tagName === "tbody" && node.attributes && node.attributes.id === id) {
      return node;
    }
    const kids = node.children;
    if (Array.isArray(kids)) {
      for (let i = 0; i < kids.length; i++) { stack.push(kids[i]); }
    }
  }
  return null;
}

/**
 * Extracts the direct tr children of a tbody as keyed VDOM entries.
 * @function extractRows
 * @param {Object} tbody - The tbody VDOM node
 * @returns {Array<{key: string|null, vdom: Object}>}
 */
function extractRows(tbody) {
  const rows = [];
  const kids = tbody.children;
  for (let i = 0; i < kids.length; i++) {
    const child = kids[i];
    if (child.tagName === "tr") {
      const attrs = child.attributes || {};
      rows.push({ key: attrs["data-key"] !== undefined ? attrs["data-key"] : null, vdom: child });
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
 * removed rows as VDOM), or "full" (main thread replaces the whole tbody).
 * @function handleDiff
 * @param {MessagePort} port - The requesting port
 * @param {string} url - The snapshot key
 * @param {string} html - The new fragment HTML
 * @param {string} tbodyId - The tbody element id
 * @returns {void}
 */
function handleDiff(port, url, html, tbodyId) {
  const root = VdomParser.parse(html);
  const tbody = findTbody(root, tbodyId);
  if (!tbody) {
    postResult(port, url, { action: "full" });
    return;
  }
  const rows = extractRows(tbody);
  const keys = rows.map(r => r.key);
  if (keys.some(k => k === null)) {
    postResult(port, url, { action: "full", vdom: tbody });
    return;
  }
  const newRows = new Map(rows.map(r => [r.key, JSON.stringify(r.vdom)]));
  const snapshot = snapshots.get(url);
  if (!snapshot) {
    snapshots.set(url, { keys, rows: newRows });
    postResult(port, url, { action: "full", vdom: tbody });
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
    postResult(port, url, { action: "full", vdom: tbody });
    return;
  }

  const rowVdom = new Map(rows.map(r => [r.key, r.vdom]));
  const changed = [];
  for (const k of survivorOrder) {
    if (newRows.get(k) !== oldRows.get(k)) { changed.push(rowVdom.get(k)); }
  }
  const inserts = inserted.map(k => ({
    key: k,
    vdom: rowVdom.get(k),
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
    if (!url || !html) { return; }
    if (tbodyId) {
      handleDiff(port, url, html, tbodyId);
    } else {
      port.postMessage({ url, action: "parsed", vdom: VdomParser.parse(html) });
    }
  };
};
