/**
 * @module tablesort
 * @description Sortable HTML tables with auto-detected column types.
 * All sorting is delegated to a dedicated web worker (sortWorker.js), with a
 * main-thread fallback when Workers are unavailable.
 * Comparison functions shared between the main thread and the worker
 * via sortShared.js. Extends Tablesort.extend().
 * Derived from tristen/tablesort (MIT) https://github.com/tristen/tablesort;
 * worker integration, empty-last sort, keyboard nav, and direction swap
 * by dr|z3d for I2P+ (AGPLv3).
 * @license AGPLv3 or later
 */

;(function() {
  /**
   * @param {HTMLTableElement} el
   * @param {Object} [options]
   * @param {boolean} [options.descending] - Default to descending on first click
   */
  function Tablesort(el, options) {
    if (!(this instanceof Tablesort)) return new Tablesort(el, options);
    if (!el || el.tagName !== "TABLE") throw new Error("Element must be a table");
    this.init(el, options || {});
  }

  let sortWorker = null;
  let sortGen = 0;
  const getSortWorker = () => {
    if (sortWorker) return sortWorker;
    try {
      // Fall back to main-thread sorting when Workers are unavailable (private browsing,
      // some embedded browsers) or the worker script fails to load.
      sortWorker = new Worker("/js/tablesort/sortWorker.js");
    } catch (e) {
      sortWorker = null;
    }
    return sortWorker;
  };

  /** Registered sort extensions (name, pattern, sort). */
  const sortOptions = [];

  /**
   * Create a CustomEvent (old-browser fallback).
   * @param {string} name
   * @returns {CustomEvent}
   */
  const createEvent = (name) =>
    typeof CustomEvent === "function" ? new CustomEvent(name) : (() => {
      const evt = document.createEvent("CustomEvent");
      evt.initCustomEvent(name, false, false, undefined);
      return evt;
    })();

  /**
   * Read cell text; prefers data-sort attribute.
   * @param {HTMLElement} el
   * @returns {string}
   */
  const getInnerText = (el) => el.getAttribute("data-sort") ?? el.textContent ?? el.innerText ?? "";

  /**
   * Find a cell by data-sort-column-key attribute.
   * @param {HTMLCollection} cells
   * @param {string} key
   * @returns {HTMLElement|undefined}
   */
  const getCellByKey = (cells, key) => Array.from(cells).find(cell => cell.getAttribute("data-sort-column-key") === key);

  /**
   * Register a named sort extension.
   * @param {string} name
   * @param {function(string): boolean} pattern - Auto-detection predicate
   * @param {function(string, string): number} sort - Ascending comparator
   */
  Tablesort.extend = (name, pattern, sort) => {
    if (typeof pattern !== "function" || typeof sort !== "function")
      throw new Error("Pattern and sort must be functions");
    sortOptions.push({ name, pattern, sort });
  };

  Tablesort.prototype = {
    /**
     * Attach click and keydown listeners to header cells.
     * Skips cells with data-sort-method="none".
     * @param {HTMLTableElement} el
     * @param {Object} options
     */
    init(el, options) {
      this.table = el; this.options = options;
      this.thead = !!el.tHead && el.tHead.rows.length > 0;
      let headerRow = this.thead ? [...el.tHead.rows].find(r => r.getAttribute("data-sort-method") === "thead") || el.tHead.rows[el.tHead.rows.length - 1] : el.rows[0];
      if (!headerRow) return;
      const onClick = e => {
        if (this.current && this.current !== e.currentTarget) this.current.removeAttribute("aria-sort");
        this.current = e.currentTarget; this.sortTable(this.current);
      };
      for (const cell of headerRow.cells) {
        cell.setAttribute("role", "columnheader");
        if (cell.getAttribute("data-sort-method") !== "none") {
          cell.tabIndex = 0;
          cell.addEventListener("click", onClick);
          cell.addEventListener("keydown", event => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault();
              onClick.call(cell, event);
            }
          });
          if (cell.hasAttribute("data-sort-default")) this.current = cell;
        }
      }
      if (this.current) this.sortTable(this.current);
    },

    /**
     * Sort the table by the clicked header.
     * Dispatches beforeSort / afterSort events.
     * Delegates to the web worker for all row counts.
     * @param {HTMLElement} header
     * @param {boolean} [update] - True to refresh without toggling direction
     */
    sortTable(header, update) {
      const columnKey = header.getAttribute("data-sort-column-key"), column = header.cellIndex;
      const sortMethod = header.getAttribute("data-sort-method"), sortOrder = header.getAttribute("aria-sort");
      const caseInsensitive = header.hasAttribute("data-sort-caseinsensitive");

      this.table.dispatchEvent(createEvent("beforeSort"));

      this.sortWithWorker(header, update, columnKey, column, sortMethod, sortOrder, caseInsensitive);
    },

    /**
     * Sort table rows. Extracts row text and determines the sort type, then sorts via
     * the web worker, or on the main thread when Workers are unavailable, re-appending
     * rows in sorted order within a single DocumentFragment.
     * @param {HTMLElement} header
     * @param {boolean} update
     * @param {string|null} columnKey
     * @param {number} column
     * @param {string|null} sortMethod
     * @param {string} sortOrder
     * @param {boolean} [caseInsensitive]
     */
    sortWithWorker(header, update, columnKey, column, sortMethod, sortOrder, caseInsensitive) {
      sortGen++;
      const gen = sortGen;
      const tbody = this.table.tBodies[0];
      if (!tbody) return;

      const rowData = [];
      const rowElements = [];
      for (let j = 0; j < tbody.rows.length; j++) {
        const row = tbody.rows[j];
        if (row.getAttribute("data-sort-method") === "none") continue;
        const cell = columnKey ? getCellByKey(row.cells, columnKey) : row.cells[column];
        const text = cell ? getInnerText(cell) : "";
        rowData.push({
          td: caseInsensitive ? text.toLowerCase() : text,
          index: j
        });
        rowElements.push(row);
      }

      let columnType = "string";
      const knownTypes = ["number","date","natural","dotsep","filesize","monthname","intl"];
      if (sortMethod) {
        if (knownTypes.includes(sortMethod)) {columnType = sortMethod;}
      } else {
        if (!caseInsensitive) {
          const sampleItems = rowData.slice(0, 3).map(r => r.td).filter(t => t);
          for (const opt of sortOptions) {
            if (sampleItems.every(opt.pattern)) {
              columnType = opt.name;
              break;
            }
          }
        }
      }

      if (!update) {
        const columnDirection = header.getAttribute("data-sort-direction");
        let defaultDescending = this.options.descending;
        if (columnDirection === "ascending") defaultDescending = false;
        else if (columnDirection === "descending") defaultDescending = true;
        sortOrder = sortOrder === "ascending" ? "descending" :
                    sortOrder === "descending" ? "ascending" :
                    defaultDescending ? "descending" : "ascending";
        header.setAttribute("aria-sort", sortOrder);
      }

      this.col = column;
      const direction = sortOrder;

      const handleMessage = (e) => {
        if (gen !== sortGen) return;
        const sorted = e.data.sorted;
        worker.removeEventListener("message", handleMessage);

        const fragment = document.createDocumentFragment();
        sorted.forEach(item => {
          fragment.appendChild(rowElements[item.index]);
        });
        tbody.appendChild(fragment);

        this.table.dispatchEvent(createEvent("afterSort"));
      };

      const worker = getSortWorker();
      if (worker) {
        worker.addEventListener("message", handleMessage);
        worker.postMessage({
          rows: rowData,
          sortColumn: "td",
          direction,
          columnType
        });
      } else {
        // Worker unavailable (private browsing, some embedded browsers). Sort on the main
        // thread with the same shared comparators. Events fire synchronously so the
        // afterSort handler runs the same as with a worker.
        const sorted = sortRows([...rowData], "td", direction, columnType);
        const fragment = document.createDocumentFragment();
        sorted.forEach(item => {
          fragment.appendChild(rowElements[item.index]);
        });
        tbody.appendChild(fragment);
        this.table.dispatchEvent(createEvent("afterSort"));
      }
    },

    /**
     * Re-apply current sort without toggling direction.
     * Use after row data changes.
     */
    refresh() {
      if (this.current) this.sortTable(this.current, true);
    }
  }

  // Register all sort extensions (shared with worker via sortShared.js)
  Tablesort.extend("number", numberPattern, numberCmpEL);
  Tablesort.extend("date", datePattern, dateCmpEL);
  Tablesort.extend("natural", naturalPattern, naturalCmpEL);
  Tablesort.extend("dotsep", dotsepPattern, dotsepCmpEL);
  Tablesort.extend("filesize", filesizePattern, filesizeCmpEL);
  Tablesort.extend("monthname", monthnamePattern, monthnameCmpEL);
  Tablesort.extend("intl", intlPattern, intlCmpEL);

  if (typeof module !== "undefined" && module.exports) module.exports = Tablesort;
  else window.Tablesort = Tablesort;
})();
