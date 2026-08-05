/**
 * @module refreshElements
 * @description Refreshes DOM elements via fetch using a SharedWorker for background
 * requests. Uses morphdom for efficient DOM diffing and supports visibility-based
 * refresh scheduling. Each refreshElements() call runs its own refresh loop, fetch
 * port, and patch state, so multiple loops can coexist on one page.
 *
 * Fragment mode: with the fragmentIds parameter the fetch URL gains a
 * contentonly parameter and the server renders only the named elements.
 * Row-diff mode: with the diffRows parameter set to a tbody id, rows of that
 * tbody are diffed in a SharedWorker (diffWorker.js) against the previous
 * snapshot and only changed rows are patched, skipping the main-thread parse
 * and morphdom pass entirely when nothing changed. In row-diff mode the
 * response is never patched via morphdom, so companion elements must use
 * their own refreshElements call.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

import morphdom from "/js/morphdom.js";

/**
 * Reports the page's visibility to the SharedWorker so the fetch worker can
 * suspend requests for tabs that aren't visible.
 * @function reportVisibility
 * @param {MessagePort} port - The fetch worker port
 * @returns {void}
 */
function reportVisibility(port) {
  port.postMessage({ visibility: !document.hidden });
}

/**
 * Splits a selector string or array into trimmed selector strings.
 * @function normalizeSelectors
 * @param {string|string[]} targetSelectors - CSS selector(s)
 * @returns {string[]}
 */
function normalizeSelectors(targetSelectors) {
  if (typeof targetSelectors === "string") {
    return targetSelectors.split(",").map(s => s.trim());
  }
  if (Array.isArray(targetSelectors)) {
    return targetSelectors.map(s => s.trim());
  }
  return [];
}

/**
 * Joins fragment element ids into a comma-separated contentonly parameter value.
 * @function normalizeFragmentIds
 * @param {string|string[]} fragmentIds - Element ids rendered by the server
 * @returns {string|null} The joined id list, or null when not using fragments
 */
function normalizeFragmentIds(fragmentIds) {
  if (!fragmentIds) { return null; }
  const ids = typeof fragmentIds === "string" ? fragmentIds.split(",") : fragmentIds;
  return ids.map(s => s.trim()).filter(s => s.length > 0).join(",") || null;
}

/**
 * Appends the contentonly parameter to a fetch URL.
 * @function appendContentOnly
 * @param {string} url - The fetch URL
 * @param {string} ids - The comma-joined element ids
 * @returns {string} The URL with the contentonly parameter appended
 */
function appendContentOnly(url, ids) {
  return url + (url.includes("?") ? "&" : "?") + "contentonly=" + ids;
}

/**
 * Parses a row HTML string into a tr element. A bare <tr> outside a table is
 * ignored by the HTML parser, so the row is wrapped in a table body.
 * @function parseRow
 * @param {string} html - The row HTML
 * @returns {HTMLElement|null} The parsed tr element
 */
function parseRow(html) {
  const doc = new DOMParser().parseFromString("<table><tbody>" + html + "</tbody></table>", "text/html");
  return doc.querySelector("tr");
}

/**
 * Sets up periodic element refresh using a SharedWorker for fetch requests.
 * Automatically pauses when the document is hidden and resumes on visibility.
 * Each call registers an independent loop; multiple calls may run concurrently.
 * @function refreshElements
 * @param {string|string[]} targetSelectors - CSS selector(s) for elements to refresh
 * @param {string} url - The URL to fetch content from
 * @param {number} delay - The refresh interval in milliseconds
 * @param {boolean} [immediate=false] - Fetch right away on setup, or wait for the first interval tick
 * @param {boolean} [silent=false] - Skip the progress bar on each refresh
 * @param {string|string[]} [fragmentIds=null] - Element ids the server should render (contentonly fragment mode)
 * @param {string} [diffRows=null] - Id of a tbody whose rows are diffed in a worker; only changed rows are patched
 * @returns {Function} The stop function that halts the refresh loop
 * @example refreshElements("#sidebar", "/sidebar", 10000)
 * @example refreshElements(["#peers", "#status"], "/peers", 5000)
 */
export function refreshElements(targetSelectors, url, delay, immediate = false, silent = false, fragmentIds = null, diffRows = null) {
  const selectors = normalizeSelectors(targetSelectors);
  const contentOnlyIds = normalizeFragmentIds(fragmentIds);
  const fetchUrl = contentOnlyIds ? appendContentOnly(url, contentOnlyIds) : url;

  const fetchWorker = new SharedWorker("/js/fetchWorker.js");
  fetchWorker.port.start();
  const visibilityListener = () => reportVisibility(fetchWorker.port);
  document.addEventListener("visibilitychange", visibilityListener);
  reportVisibility(fetchWorker.port);

  const diffWorker = diffRows ? new SharedWorker("/js/diffWorker.js") : null;
  if (diffWorker) { diffWorker.port.start(); }

  let instanceIntervalId = null;
  let isRefreshing = false;

  /**
   * Dispatches the refresh events after a patch is applied.
   * @function dispatchDone
   * @returns {void}
   */
  function dispatchDone() {
    document.dispatchEvent(new Event("refreshComplete"));
    document.dispatchEvent(new CustomEvent("elementsRefreshed", { detail: { selectors } }));
  }

  /**
   * Applies a row-level diff result to the live tbody. Inserted rows carry
   * their target position (the key of the following row in server order) and
   * are inserted before it; an unknown predecessor falls back to appending,
   * which the sorter refresh on refreshComplete corrects when the user has
   * an active sort.
   * @function patchRows
   * @param {string} tbodyId - The tbody element id
   * @param {Object} result - The diff result (changed, inserts, removed)
   * @returns {boolean} True when a lazy row was inserted (re-scan needed)
   */
  function patchRows(tbodyId, result) {
    const tbody = document.getElementById(tbodyId);
    if (!tbody) { return false; }
    let lazyAdded = false;
    const rowByKey = key => tbody.querySelector('tr[data-key="' + key + '"]');
    const morphOptions = {
      onBeforeElUpdated: (fromEl, toEl) => {
        if (fromEl.isEqualNode(toEl)) { return false; }
        return true;
      }
    };
    (result.changed || []).forEach(html => {
      const row = parseRow(html);
      if (!row) { return; }
      const key = row.getAttribute("data-key");
      const existing = key ? rowByKey(key) : null;
      if (existing) { morphdom(existing, row, morphOptions); }
    });
    (result.inserts || []).slice().reverse().forEach(insert => {
      const row = parseRow(insert.html);
      if (!row) { return; }
      if (row.classList.contains("lazy")) { lazyAdded = true; }
      const before = insert.before ? rowByKey(insert.before) : null;
      if (before) { tbody.insertBefore(row, before); }
      else { tbody.appendChild(row); }
    });
    (result.removed || []).forEach(key => {
      const row = rowByKey(key);
      if (row) { row.remove(); }
    });
    return lazyAdded;
  }

  /**
   * Full fallback: replace the tbody contents wholesale (first tick, row
   * order changes, or rows without keys).
   * @function patchFull
   * @param {string} tbodyId - The tbody element id
   * @param {string} tbodyHtml - The tbody HTML from the diff worker
   * @returns {{changed: boolean, lazyAdded: boolean}} Whether the DOM changed and whether new lazy rows were added
   */
  function patchFull(tbodyId, tbodyHtml) {
    const tbody = document.getElementById(tbodyId);
    if (!tbody || !tbodyHtml) { return { changed: false, lazyAdded: false }; }
    let changed = false;
    let lazyAdded = false;
    // A bare <tbody> outside a table is ignored by the HTML parser, so the
    // whole-tbody fragment is wrapped before parsing.
    const doc = new DOMParser().parseFromString("<table>" + tbodyHtml + "</table>", "text/html");
    const newTbody = doc.getElementById(tbodyId);
    if (!newTbody) { return { changed: false, lazyAdded: false }; }
    morphdom(tbody, newTbody, {
      onBeforeElUpdated: (fromEl, toEl) => {
        if (fromEl.isEqualNode(toEl)) { return false; }
        return true;
      },
      onElUpdated: () => { changed = true; },
      onNodeAdded: (el) => {
        changed = true;
        if (el.classList && el.classList.contains("lazy")) { lazyAdded = true; }
      }
    });
    return { changed, lazyAdded };
  }

  /**
   * Patch the document from a fetched response with morphdom (no row diff).
   * @function patchResponse
   * @param {string} responseText - The fetched fragment HTML
   * @returns {void}
   */
  function patchResponse(responseText) {
    let lazyAdded = false;
    const doc = new DOMParser().parseFromString(responseText, "text/html");
    selectors.forEach(selector => {
      const targetElements = document.querySelectorAll(selector);
      const targetElementsResponse = doc.querySelectorAll(selector);
      targetElements.forEach((targetElement, index) => {
        const targetElementResponse = targetElementsResponse[index];
        if (targetElement && targetElementResponse) {
          morphdom(targetElement, targetElementResponse, {
            onBeforeElUpdated: (fromEl, toEl) => {
              if (fromEl.isEqualNode(toEl)) { return false; }
              return true;
            },
            onNodeAdded: (el) => {
              if (el.classList && el.classList.contains("lazy")) { lazyAdded = true; }
            }
          });
        }
      });
    });
    if (lazyAdded) { document.dispatchEvent(new Event("elementsPatched")); }
    dispatchDone();
  }

  fetchWorker.port.onmessage = function(e) {
    const { responseText } = e.data;
    if (!responseText) { return; }

    const arrivedHidden = document.hidden;
    requestAnimationFrame(() => {
      // Response fetched while the tab was hidden: drop it. rAF is suspended
      // while hidden, so it would otherwise render stale data on regain before
      // the visibilitychange-triggered refresh replaces it.
      if (arrivedHidden || document.hidden) { return; }
      if (diffWorker) {
        diffWorker.port.postMessage({ url: fetchUrl, html: responseText, tbodyId: diffRows });
      } else {
        patchResponse(responseText);
      }
    });
  };

  if (diffWorker) {
    diffWorker.port.onmessage = function(e) {
      const data = e.data;
      if (!data || data.action === "unchanged") { return; }
      if (data.action === "rows") {
        const lazyAdded = patchRows(diffRows, data);
        if (lazyAdded) { document.dispatchEvent(new Event("elementsPatched")); }
        dispatchDone();
      } else if (data.action === "full") {
        const result = patchFull(diffRows, data.tbodyHtml);
        if (result.lazyAdded) { document.dispatchEvent(new Event("elementsPatched")); }
        if (result.changed) { dispatchDone(); }
      }
    };
  }

  function refresh() {
    if (document.visibilityState !== "visible" || isRefreshing) { return; }

    isRefreshing = true;
    if (!silent) { progressx?.show(theme); }

    fetchWorker.port.postMessage({ url: fetchUrl });

    setTimeout(() => {
      if (!silent) { progressx?.hide(); }
      isRefreshing = false;
    }, 1000);
  }

  if (document.visibilityState === "visible") {
    if (immediate) { refresh(); }
    instanceIntervalId = setInterval(refresh, delay);
  }

  function handleVisibilityChange() {
    if (document.visibilityState === "visible") {
      refresh();
      if (!instanceIntervalId) {
        instanceIntervalId = setInterval(refresh, delay);
      }
    } else {
      clearInterval(instanceIntervalId);
      instanceIntervalId = null;
      isRefreshing = false;
    }
  }
  document.addEventListener("visibilitychange", handleVisibilityChange);

  /**
   * Halts the refresh loop for this instance.
   * @function stop
   * @returns {void}
   */
  function stop() {
    clearInterval(instanceIntervalId);
    instanceIntervalId = null;
    isRefreshing = false;
    document.removeEventListener("visibilitychange", handleVisibilityChange);
    document.removeEventListener("visibilitychange", visibilityListener);
    fetchWorker.port.close();
    if (diffWorker) { diffWorker.port.close(); }
  }

  return stop;
}
