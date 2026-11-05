/**
 * @module refreshLogs
 * @description Manages auto-refresh for log pages (router logs, error logs, service logs)
 * using a SharedWorker for background fetches and morphdom for efficient DOM updates.
 * Supports configurable refresh intervals, log filtering, and linkification of
 * router IDs, lease hashes, and IP addresses in log entries.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { onVisible, onHidden } from "/js/onVisible.js";
import morphdom from "/js/morphdom.js";
import { isLogTag } from "./logTags.js";

/**
 * Converts router IDs, lease hashes, and IP addresses in log entries into
 * clickable links pointing to the relevant /netdb pages. Exported for the
 * harness tests, which drive it against a linkedom document.
 * @function linkifyLogEntries
 * @param {HTMLElement} container - The container element with log entries
 * @returns {void}
 */
export function linkifyLogEntries(container) {
  if (!container) { return; }

  const isValidPort = s => {
    const n = +s;
    return n >= 1 && n <= 65535 && s === String(n);
  };

  const leasePattern = /(?:key\s*)?\$([a-zA-Z0-9~\-]{8})\$|#ls_([a-zA-Z0-9]{4})\b|\[([a-zA-Z0-9~\-]{8})\]/g;
  const ipv4PortPattern = /\b((?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b(?::(\d{1,5}))?\b/g;
  const ipv6PortPattern = /(?:\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|\b(?:[0-9a-fA-F]{1,4}:){1,7}:)(?::(\d{1,5}))?\b/gi;

  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      return node.parentElement?.tagName === "A" ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
    }
  }, false);

  const textNodes = [];
  for (let node; (node = walker.nextNode()); ) {
    textNodes.push(node);
  }

  textNodes.forEach(textNode => {
    const parent = textNode.parentElement;
    if (!parent) return;

    const text = textNode.textContent;
    const fragments = [];
    let cursor = 0;
    const matches = [];

    const addMatches = (pattern, getMatches) => {
      let match;
      pattern.lastIndex = 0;
      while ((match = pattern.exec(text)) !== null) {
        const results = getMatches(match);
        if (results) matches.push(...results);
      }
    };

    addMatches(/floodfill\s*\[([a-zA-Z0-9~\-]{6})\]/gi, m => [{
      start: m.index,
      end: m.index + m[0].length,
      clean: {
        id: m[1],
        href: `/netdb?r=${m[1]}`,
        isFF: true
      }
    }]);

    addMatches(/router\s*\[([a-zA-Z0-9~\-]{6})\]/gi, m => [{
      start: m.index,
      end: m.index + m[0].length,
      clean: {
        id: m[1],
        href: `/netdb?r=${m[1]}`,
        isFF: false
      }
    }]);

    addMatches(/\[([a-zA-Z0-9~\-]{6})\]/g, m => {
      // A bracketed tag such as the leading [Eepget] is a label, not a router
      if (isLogTag(m[1])) { return []; }
      return [{
        start: m.index,
        end: m.index + m[0].length,
        clean: {
          id: m[1],
          href: `/netdb?r=${m[1]}`,
          isFF: false
        }
      }];
    });

    addMatches(leasePattern, m => {
      const id = m[1] || m[2] || m[3];
      if (!id) return [];
      // The bracketed arm only: $hash$ and #ls_hash name a lease set outright
      if (m[3] && isLogTag(m[3])) { return []; }
      return [{ start: m.index, end: m.index + m[0].length, clean: { id, href: `/netdb?l=3#${id.substring(0, 4)}` } }];
    });

    addMatches(ipv4PortPattern, m => {
      const ipStart = m.index;
      const full = m[0];
      const colonIdx = full.indexOf(':');
      const ip = colonIdx === -1 ? full : full.substring(0, colonIdx);
      const ipEnd = ipStart + ip.length;
      const results = [{ start: ipStart, end: ipEnd, clean: { id: ip, href: `/netdb?ip=${ip}` } }];
      if (colonIdx !== -1) {
        const portStr = m[2];
        if (portStr && isValidPort(portStr)) {
          const portStart = ipEnd + 1;
          results.push({ start: portStart, end: portStart + portStr.length, clean: { id: portStr, href: `/netdb?port=${portStr}` } });
        }
      }
      return results;
    });

    addMatches(ipv6PortPattern, m => {
      const full = m[0];
      const ipStart = m.index;
      const portStr = m[2];
      const ipEnd = portStr ? ipStart + (full.length - portStr.length - 1) : ipStart + full.length;
      const ipRaw = full.substring(0, ipEnd - ipStart);
      const cleanIP = ipRaw.split('%')[0];
      const results = [{ start: ipStart, end: ipEnd, clean: { id: ipRaw, href: `/netdb?ipv6=${cleanIP}` } }];
      if (portStr && isValidPort(portStr)) {
        const portStart = ipEnd + 1;
        results.push({ start: portStart, end: portStart + portStr.length, clean: { id: portStr, href: `/netdb?port=${portStr}` } });
      }
      return results;
    });

    if (matches.length === 0) return;
    matches.sort((a, b) => a.start - b.start);

    const merged = [];
    for (const m of matches) {
      if (merged.length === 0 || m.start >= merged[merged.length - 1].end) {
        merged.push(m);
      }
    }

    for (const match of merged) {
      if (match.start > cursor) {
        fragments.push(document.createTextNode(text.slice(cursor, match.start)));
      }

      const link = document.createElement("a");
      link.href = match.clean.href;
      link.textContent = match.clean.id;
      if (match.clean.isFF) {link.classList.add("isFF");}
      fragments.push(link);
      cursor = match.end;
    }

    if (cursor < text.length) {
      fragments.push(document.createTextNode(text.slice(cursor)));
    }

    fragments.forEach(frag => parent.insertBefore(frag, textNode));
    parent.removeChild(textNode);
  });
}

/**
 * Drops the column separator that stands between the date and the level badge.
 *
 * The file log is pipe-delimited - date | thread | class | level | message - and
 * the pipe is what makes `grep` and `cut` work on it. On the page the pipes are
 * noise, and the one after the date is the worst of them: it sits between the
 * date and a badge that is already a visually separate box. Only that one is
 * removed, and the level badge is what locates it.
 *
 * Anchoring on the badge rather than scanning the line is what keeps this safe.
 * A hex dump in the message body carries pipes in its own column framing
 * (`00000000 68 65 ...|he..|`), and the date format is configurable on
 * /configlogging, so neither "the first pipe on the line" nor a date pattern
 * matched both. The badge is the one thing on the line that is always the level:
 * the server wraps it in span.log_level when it rewrites the level, and the
 * separator it writes is always the two characters immediately before it.
 *
 * Idempotent - a line already stripped has no separator left to find - so it is
 * run over the whole list on every refresh, which also covers the entries
 * morphdom keeps from the previous render.
 *
 * @function stripLevelSeparator
 * @param {HTMLElement} container - The container element with log entries
 * @returns {void}
 */
export function stripLevelSeparator(container) {
  if (!container) { return; }
  for (const li of container.querySelectorAll("li")) {
    const badge = li.querySelector("span.log_level");
    if (!badge) { continue; }
    const prev = badge.previousSibling;
    // The server writes "| " immediately before the badge, after the space that
    // separates the date. Dropping the pipe and its trailing space leaves that
    // one space, which is what the date and the badge should be apart by.
    if (!prev || prev.nodeType !== Node.TEXT_NODE) { continue; }
    const text = prev.data;
    if (!text.endsWith("| ")) { continue; }
    prev.data = text.slice(0, -2);
  }
}

/**
 * Show a shortened column with a real ellipsis, at its written width.
 *
 * The file log is fixed-width and keeps its truncation marker in plain ASCII -
 * `..` - on purpose, so the framing survives a viewer that decodes the file as
 * something other than UTF-8. A terminal with LANG=C renders a UTF-8 ellipsis as
 * three cells and pushes the rest of the line right; the page declares UTF-8, so
 * it can show the real character instead of the dots.
 *
 * Two cells become one, so the cell is put back: the ellipsis is followed by a
 * non-breaking space whenever the column came in short of its width. It comes in
 * short in two ways - the server's own rewrite of three consecutive dots, which
 * folds the marker and a leading dot of the tail into one character, and a
 * legacy line that already carries the ellipsis character.
 *
 * Only the two framed columns are touched, and the framing is what says so: the
 * bracketed thread cell of exactly the formatter's thread width, with the source
 * column of exactly its source width immediately after it. A ".." anywhere else
 * - a path, an elided quote, a hex dump - is text and stays text. Widths are
 * counted in cells, not characters, because the server writes a padded column's
 * spaces as &nbsp; so HTML cannot collapse them.
 *
 * The pass reads the first text node that opens a bracket: the framing sits after
 * the level badge, which splits the entry's text, and the linkifier splits it
 * further, so "the first text node" is the date, not the columns.
 *
 * Idempotent, so it runs over the whole list on every refresh.
 *
 * @function renderColumnEllipsis
 * @param {HTMLElement} container - The container element with log entries
 * @returns {void}
 */
export function renderColumnEllipsis(container) {
  if (!container) { return; }
  for (const li of container.querySelectorAll("li")) {
    let node = firstTextWithBracket(li);
    if (!node) { continue; }
    // Adjacent text nodes are one run of text to the reader, and a parser is free
    // to split that run wherever it likes, so the columns may straddle a boundary.
    // The run is read as one string and written back as one node.
    const run = [node];
    for (let sib = node.nextSibling; sib && sib.nodeType === 3; sib = sib.nextSibling) {
      run.push(sib);
    }
    // Everything here stays in the response's own form, entities included: the
    // padding the server wrote is &nbsp;, and a plain space would collapse.
    const text = run.map((n) => n.data).join("");
    const lb = text.indexOf("[");
    const rb = text.indexOf("] ", lb + 1);
    if (rb < 0) { continue; }
    const threadRaw = text.slice(lb + 1, rb);
    if (!isColumn(cellCount(threadRaw), THREAD_COLUMN_WIDTH)) { continue; }
    // The source column runs from just after the thread cell to the colon the
    // format puts after it. Its width is what says this is that column and not a
    // message that happens to open with a bracket - a colon inside a class name
    // is common, so the colon that closes it is the one at a width this column
    // can have. A run that ends before the source column does, because the
    // response put markup in the message, carries no evidence either way, and the
    // thread cell's own width is evidence enough.
    const src = columnEnd(text, rb + 2, SOURCE_COLUMN_WIDTH);
    if (src.end < 0) { continue; }
    // A column the run cuts off has no closing colon to work from: everything
    // after the thread cell is left exactly as it is, and only the thread cell
    // is rendered.
    const srcRaw = src.whole ? text.slice(rb + 2, src.end) : "";
    if (src.whole && !isColumn(cellCount(srcRaw), SOURCE_COLUMN_WIDTH)) { continue; }
    const newThread = renderEllipsisField(threadRaw, THREAD_COLUMN_WIDTH);
    const newSource = src.whole ? renderEllipsisField(srcRaw, SOURCE_COLUMN_WIDTH) : srcRaw;
    if (newThread === threadRaw && newSource === srcRaw) { continue; }
    // With no closing colon in sight, everything after the thread cell is the
    // message and stays where it is.
    node.data = src.whole
        ? text.slice(0, lb + 1) + newThread + text.slice(rb, rb + 2) + newSource + text.slice(src.end)
        : text.slice(0, lb + 1) + newThread + text.slice(rb);
    for (let i = 1; i < run.length; i++) { run[i].data = ""; }
  }
}

/** The thread column's width, as net.i2p.util.LogRecordFormatter pads it. */
const THREAD_COLUMN_WIDTH = 12;
/** The source column's width, as net.i2p.util.LogRecordFormatter pads it. */
const SOURCE_COLUMN_WIDTH = 16;
/** The formatter's ASCII truncation marker. */
const FILE_MARKER = "..";
/** The character the page shows for it. */
const ELLIPSIS = "\u2026";
/**
 * The cell the two-character marker holds and the one-character ellipsis does
 * not. Non-breaking, unlike a plain space, so HTML cannot collapse it.
 */
const NBSP = "\u00a0";

/**
 * Whether a field is the width a framed column can be.
 *
 * The formatter writes exactly the column's width. The server's own rewrite of
 * three consecutive dots is one cell short of that - it folds the two-character
 * truncation marker together with a leading dot of the tail into a single
 * ellipsis - and that is the only form that arrives short.
 *
 * @param {number} cells - the field's width in cells
 * @param {number} width - the column's width
 * @returns {boolean} whether the field is that column
 */
function isColumn(cells, width) {
  return cells === width || cells === width - 1;
}

/**
 * Render one column's truncation marker as the real ellipsis, keeping the field
 * at the width the formatter padded it to.
 *
 * The formatter writes a truncated name as its marker plus whatever tail fits,
 * always exactly the column's width. The ellipsis is one cell where the marker
 * is two, so the field comes out short and is padded back up. Two other shapes
 * reach the page already folded: where the tail opened with a dot, the server's
 * rewrite of three consecutive dots took a second cell, and a legacy line
 * carries the one-character ellipsis where the marker was. Padding to the width
 * rather than adding a fixed amount gets all three right, and leaves the field
 * alone when it is already at the width.
 *
 * A field at the full width that opens with two dots may be a name that
 * genuinely starts with them; the formatter writes a marker only when it had to
 * drop characters, and a padded name is the same width either way, so the two
 * cannot be told apart here. Drawing the ellipsis anyway costs nothing but the
 * rare name that begins with dots.
 *
 * @param {string} field - the column's text
 * @param {number} width - the column's width
 * @returns {string} the field, with the marker rendered
 */
function renderEllipsisField(field, width) {
  const marker = field.startsWith("...") ? 3
                 : field.startsWith(FILE_MARKER) ? 2
                 : field.startsWith(ELLIPSIS) ? 1 : -1;
  if (marker < 0) { return field; }
  let out = ELLIPSIS + field.slice(marker);
  while (cellCount(out) < width) { out += NBSP; }
  return out;
}

/**
 * How many cells a column's text draws. The server writes a padded column's
 * spaces as &nbsp; so HTML cannot collapse them, and an entity is one cell drawn
 * from several characters, so the character count and the cell count are not the
 * same thing.
 *
 * @param {string} raw - the column's text
 * @returns {number} the cells it draws
 */
function cellCount(raw) {
  let cells = 0;
  for (let i = 0; i < raw.length; cells++) {
    if (raw.startsWith("&nbsp;", i)) {i += 6;}
    else if (raw.startsWith("&hellip;", i)) {i += 8;}
    else {i += 1;}
  }
  return cells;
}

/**
 * Where a framed column ends. A class name may carry a colon of its own, so the
 * first colon after the column opens is not the colon that closes it; the colon
 * that does is the one at a width this column can have.
 *
 * @param {string} text - the text to read
 * @param {number} from - where the column's text starts
 * @param {number} width - the column's width
 * @returns {{end: number, whole: boolean}} the offset of the colon that closes
 *          the column with whole set; the offset the node ends at, with whole
 *          unset, when the node was cut off before the column was; and end -1
 *          when a line ends first or what is closed there is no such column
 */
function columnEnd(text, from, width) {
  let i = from;
  let cells = 0;
  while (cells < width && i < text.length) {
    // A multi-line entry is split by the page into separate lines; the break is
    // not part of the column and does not count against its width.
    if (text.charAt(i) === "\n") {i += 1; continue;}
    if (text.startsWith("...", i)) {i += 3;}               // the marker and a dot
    else if (text.startsWith("&nbsp;", i)) {i += 6;}       // a padded space, one cell
    else if (text.startsWith("&hellip;", i)) {i += 8;}
    else {i += 1;}
    cells++;
  }
  // Out of text before the column was: nothing after the thread cell can be read
  // as a column, but the thread cell itself still can be.
  if (i >= text.length) {return {end: i, whole: false};}
  // A class name may carry a colon of its own, so the colon that closes the
  // column is not the first one after it opens; it is the one just past a field
  // of the width this column can have - the full width, or one cell short of it
  // when the server's dot rewrite folded a cell away.
  if (text.charAt(i) === ":") {return {end: i, whole: true};}
  if (text.charAt(i - 1) === ":") {return {end: i - 1, whole: true};}
  return {end: -1, whole: false};
}

/**
 * The first text node under an element that opens a bracket - where a framed
 * column starts.
 *
 * @param {HTMLElement} el - the element to search
 * @returns {Text|null} the text node, or null if none of them opens a bracket
 */
function firstTextWithBracket(el) {
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
  for (let node; (node = walker.nextNode()); ) {
    if (node.data.indexOf("[") >= 0) {return node;}
  }
  return null;
}

/**
 * Initializes log page refresh functionality including worker setup, filter input,
 * and toggle controls.
 * @function start
 * @returns {void}
 */
function start() {
  const $ = id => document.getElementById(id);

  const els = {
    mainLogs: $("logs"),
    criticallogs: $("criticallogs"),
    errorCount: $("errorCount"),
    routerlogs: $("routerlogs"),
    routerlogsList: $("routerlogs")?.querySelector("td ul"),
    routerlogsFileInfo: $("routerlogs")?.querySelector("tr:first-child td p"),
    servicelogs: $("wrapperlogs"),
    refreshSpan: $("refreshPeriod"),
    refreshInput: $("logRefreshInterval"),
    toggleRefresh: $("toggleRefresh"),
    filterInput: $("logFilterInput")
  };

  const state = {
    updates: [],
    worker: new SharedWorker("/js/fetchWorker.js"),
    logsRefreshId: null,
    intervalValue: localStorage.getItem("logsRefresh") || "30"
  };

  if ("SharedWorker" in window) {
    state.worker.port.onmessage = event => {
      const { responseText, isDown } = event.data;
      if (isDown) { return; }

      const parser = new DOMParser();
      const doc = parser.parseFromString(responseText, "text/html");

      const arrivedHidden = document.hidden;
      processUpdates(doc, els, state);
      requestAnimationFrame(() => {
        if (arrivedHidden || document.hidden) {
          state.updates = [];
          return;
        }
        state.updates.forEach(fn => fn());
        if ($("routerlogs")) {
          applyFilter(els);
        }
        state.updates = [];
      });
    };

    state.worker.port.start();
    state.worker.port.postMessage({ visibility: !document.hidden });
    document.addEventListener("visibilitychange", () => {
      state.worker.port.postMessage({ visibility: !document.hidden });
    });
    state.worker.port.postMessage({ url: "/logs" });
  }

  /**
   * Queues DOM updates for error counts, critical logs, router logs, and service logs.
   * @function processUpdates
   * @param {Document} doc - The parsed HTML document from the fetch response
   * @param {Object} els - Cached DOM element references
   * @param {Object} state - Application state including updates queue
   * @returns {void}
   */
  function processUpdates(doc, els, state) {
    if (els.errorCount) {
      const newEl = doc.getElementById("errorCount");
      if (newEl) { state.updates.push(() => morphdom(els.errorCount, newEl, { childrenOnly: true })); }
    }

    if (els.criticallogs) {
      const newEl = doc.getElementById("criticallogs");
      if (newEl) { state.updates.push(() => morphdom(els.criticallogs, newEl, { childrenOnly: true })); }
    }

    if (els.routerlogsList) {
      const newFileInfo = doc.querySelector("#routerlogs tr:first-child td p");
      const newList = doc.querySelector("#routerlogs td ul");

      if (newFileInfo) { state.updates.push(() => morphdom(els.routerlogsFileInfo, newFileInfo)); }
      if (newList) {
        const clone = newList.cloneNode(true);
        tagFirstLi(clone);
        linkifyLogEntries(clone);
        state.updates.push(() => morphdom(els.routerlogsList, clone, {
          childrenOnly: true,
          getNodeKey: el => el.nodeType === Node.ELEMENT_NODE ? el.getAttribute("data-key") : null
        }));
        // After the morph, and over the live list: the keyed diff keeps the
        // entries it already had, so working on the clone would leave those
        // untouched.
        state.updates.push(() => {
          stripLevelSeparator(els.routerlogsList);
          renderColumnEllipsis(els.routerlogsList);
        });
      }
    }

    if (els.servicelogs) {
      const newEl = doc.getElementById("wrapperlogs");
      if (newEl) { state.updates.push(() => morphdom(els.servicelogs, newEl, { childrenOnly: true })); }
    }
  }

  /**
   * Starts the log refresh interval, retrying until required elements are available.
   * @function initRefresh
   * @returns {void}
   */
  function initRefresh() {
    if (!els.mainLogs || !els.routerlogs) {
      setTimeout(initRefresh, 500);
      return;
    }
    stopRefresh();
    state.logsRefreshId = setInterval(() => refreshLogs(state, els), state.intervalValue * 1000);
    els.toggleRefresh.classList.add("enabled");
  }

  /**
   * Stops the log refresh interval timer.
   * @function stopRefresh
   * @returns {void}
   */
  function stopRefresh() {
    clearInterval(state.logsRefreshId);
  }

  /**
   * Triggers a log refresh by posting a fetch URL to the SharedWorker.
   * @function refreshLogs
   * @param {Object} state - Application state with worker and interval info
   * @param {Object} els - Cached DOM element references
   * @returns {void}
   */
  function refreshLogs(state, els) {
    if (document.hidden || !navigator.onLine) { return; }
    progressx.show(theme);
    if ($("routerlogs")) {
      state.worker.port.postMessage({ url: "/routerlogs" });
      addFilterInput(els);
    } else if ($("criticallogs")) {
      state.worker.port.postMessage({ url: "/errorlogs" });
    } else if ($("wrapperlogs")) {
      state.worker.port.postMessage({ url: "/servicelogs" });
    }
    updateInterval(state, els);
    progressx.hide();
  }

  /**
   * Reads the refresh interval from localStorage and updates the input control
   * and refresh timer accordingly.
   * @function updateInterval
   * @param {Object} state - Application state with interval value
   * @param {Object} els - Cached DOM element references
   * @returns {void}
   */
  function updateInterval(state, els) {
    state.intervalValue = localStorage.getItem("logsRefresh") || "30";

    if (els.refreshInput) {
      els.refreshInput.min = 0;
      els.refreshInput.max = 3600;
      els.refreshInput.value = state.intervalValue;
    }

    if (els.refreshInput && !els.refreshSpan.classList.contains("listening")) {
      els.refreshInput.addEventListener("input", () => {
        els.refreshSpan.classList.add("listening");
        const value = els.refreshInput.value;
        if (!value || Number.isNaN(value)) { return; }

        state.intervalValue = value;
        localStorage.setItem("logsRefresh", value);
        clearInterval(state.logsRefreshId);

        if (value === "0") {
          els.toggleRefresh.classList.replace("enabled", "disabled");
        } else {
          state.logsRefreshId = setInterval(() => refreshLogs(state, els), value * 1000);
          els.toggleRefresh.classList.replace("disabled", "enabled");
        }
      });
    }
  }

  /**
   * Adds a debounced input listener to the log filter text field.
   * @function addFilterInput
   * @param {Object} els - Cached DOM element references
   * @returns {void}
   */
  function addFilterInput(els) {
    if (!els.routerlogs) { return; }
    if (els.filterInput._listenerAdded) { return; }

    const debounce = (fn, delay) => {
      let timeout;
      return (...args) => {
        clearTimeout(timeout);
        timeout = setTimeout(() => fn(...args), delay);
      };
    };

    els.filterInput.addEventListener("input", debounce(() => applyFilter(els), 300));
    els.filterInput._listenerAdded = true;
  }

  /**
   * Filters log list items based on the current filter input value.
   * @function applyFilter
   * @param {Object} els - Cached DOM element references
   * @returns {void}
   */
  function applyFilter(els) {
    if (!$("routerlogs")) { return; }
    const filterValue = els.filterInput.value.toLowerCase();
    if (els.routerlogsList) {
      els.routerlogsList.querySelectorAll("li").forEach(li => {
        li.style.display = li.textContent.toLowerCase().includes(filterValue) ? "" : "none";
      });
    }
  }


  /**
   * Tags the first list item with a unique data-key attribute for morphdom diffing.
   * @function tagFirstLi
   * @param {HTMLElement} list - The list element to tag
   * @returns {void}
   */
  function tagFirstLi(list) {
    if (!list) { return; }
    const firstLi = list.querySelector("li");
    if (firstLi) {
      const shortKey = Date.now().toString().slice(-4);
      firstLi.setAttribute("data-key", `log-${shortKey}`);
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    onVisible(els.mainLogs, () => requestAnimationFrame(() => initRefresh()));
    onHidden(els.mainLogs, stopRefresh);
    updateInterval(state, els);
    addFilterInput(els);
    applyFilter(els);
    // The page arrives already rendered by the server, so the first paint has to
    // be fixed up too, not just the ones that arrive on refresh.
    stripLevelSeparator(els.routerlogsList);
    renderColumnEllipsis(els.routerlogsList);

    if ($("routerlogs")) {
      els.toggleRefresh.addEventListener("click", () => {
        const isOn = els.toggleRefresh.classList.contains("enabled");
        if (isOn) {
          els.toggleRefresh.classList.replace("enabled", "disabled");
          stopRefresh();
        } else {
          els.toggleRefresh.classList.replace("disabled", "enabled");
          refreshLogs(state, els);
          initRefresh();
        }
      });
    }
  });
}

start();
