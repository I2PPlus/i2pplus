/**
 * @module tablesort
 * @description Sortable HTML tables with auto-detected column types.
 * Delegates large tables (>100 rows) to a web worker. Comparison functions
 * shared with sortWorker.js via sortShared.js. Extends Tablesort.extend().
 * Derived from tristen/tablesort (MIT), modified for I2P+ (AGPLv3).
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

  /** Delegate to web worker above this row count. */
  const LARGE_TABLE_THRESHOLD = 100;
  let sortWorker = null;
  const getSortWorker = () => {
    if (!sortWorker) sortWorker = new Worker("/js/sortWorker.js");
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
   * Default fallback sort (ascending). Trims and case-folds.
   * @param {string} a
   * @param {string} b
   * @returns {number}
   */
  const caseInsensitiveSort = (a, b) => {
    a = a.trim().toLowerCase(); b = b.trim().toLowerCase();
    return a === b ? 0 : (a < b ? -1 : 1);
  };

  /**
   * Find a cell by data-sort-column-key attribute.
   * @param {HTMLCollection} cells
   * @param {string} key
   * @returns {HTMLElement|undefined}
   */
  const getCellByKey = (cells, key) => Array.from(cells).find(cell => cell.getAttribute("data-sort-column-key") === key);

  /**
   * Stable-sort wrapper. Ties resolved by original index.
   * antiStabilize reverses tie order for the inverted sort pass.
   * @param {function} sort
   * @param {boolean} antiStabilize
   * @returns {function(Object, Object): number}
   */
  const stabilize = (sort, antiStabilize) => (a, b) => {
    const res = sort(a.td, b.td);
    return res === 0 ? (antiStabilize ? b.index - a.index : a.index - b.index) : res;
  };

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
     * Delegates to web worker for tables > LARGE_TABLE_THRESHOLD rows.
     * @param {HTMLElement} header
     * @param {boolean} [update] - True to refresh without toggling direction
     */
    sortTable(header, update) {
      const columnKey = header.getAttribute("data-sort-column-key"), column = header.cellIndex;
      let sortFunction = caseInsensitiveSort, sortMethod = header.getAttribute("data-sort-method"), sortOrder = header.getAttribute("aria-sort");

      this.table.dispatchEvent(createEvent("beforeSort"));

      const totalRows = this.table.tBodies[0]?.rows.length || 0;
      if (totalRows > LARGE_TABLE_THRESHOLD) {
        this.sortWithWorker(header, update, columnKey, column, sortMethod, sortOrder);
        return;
      }

      this.sortNative(header, update, columnKey, column, sortFunction, sortMethod, sortOrder);
    },

    /**
     * Sort via web worker. Extracts row text, determines sort type,
     * sends to worker, re-appends rows on response.
     * @param {HTMLElement} header
     * @param {boolean} update
     * @param {string|null} columnKey
     * @param {number} column
     * @param {string|null} sortMethod
     * @param {string} sortOrder
     */
    sortWithWorker(header, update, columnKey, column, sortMethod, sortOrder) {
      const worker = getSortWorker();
      const rowData = [];
      const tbody = this.table.tBodies[0];
      if (!tbody) return;

      const rowElements = [];
      for (let j = 0; j < tbody.rows.length; j++) {
        const row = tbody.rows[j];
        if (row.getAttribute("data-sort-method") === "none") continue;
        const cell = columnKey ? getCellByKey(row.cells, columnKey) : row.cells[column];
        rowData.push({
          td: cell ? getInnerText(cell) : "",
          index: j
        });
        rowElements.push(row);
      }

      let columnType = "string";
      const knownTypes = ["number","date","natural","dotsep","filesize","monthname","intl"];
      if (sortMethod) {
        if (knownTypes.includes(sortMethod)) {columnType = sortMethod;}
      } else {
        const sampleItems = rowData.slice(0, 3).map(r => r.td).filter(t => t);
        for (const opt of sortOptions) {
          if (sampleItems.every(opt.pattern)) {
            columnType = opt.name;
            break;
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
        const sorted = e.data.sorted;
        worker.removeEventListener("message", handleMessage);

        const fragment = document.createDocumentFragment();
        sorted.forEach(item => {
          const originalIndex = item.index;
          fragment.appendChild(rowElements[originalIndex]);
        });
        tbody.appendChild(fragment);

        this.table.dispatchEvent(createEvent("afterSort"));
      };

      worker.addEventListener("message", handleMessage);
      worker.postMessage({
        rows: rowData,
        sortColumn: "td",
        direction,
        columnType
      });
    },

    /**
     * Sort small tables synchronously on the main thread.
     * Uses registered sort extensions; falls back to case-insensitive.
     * @param {HTMLElement} header
     * @param {boolean} update
     * @param {string|null} columnKey
     * @param {number} column
     * @param {function} sortFunction
     * @param {string|null} sortMethod
     * @param {string} sortOrder
     */
    sortNative(header, update, columnKey, column, sortFunction, sortMethod, sortOrder) {
      window.requestAnimationFrame(() => {
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

        if (this.table.rows.length < 2) return;

        const tbodyRows = this.table.tBodies[0]?.rows || [], sampleItems = [];
        let rowIndex = this.thead ? 0 : 1;

        if (!sortMethod) {
          while (sampleItems.length < 3 && rowIndex < tbodyRows.length) {
            const cell = columnKey ? getCellByKey(tbodyRows[rowIndex].cells, columnKey) : tbodyRows[rowIndex].cells[column];
            const value = (cell ? getInnerText(cell) : "").trim();
            if (value) sampleItems.push(value);
            rowIndex++;
          }
          if (!sampleItems.length) return;
        }

        for (const option of sortOptions) {
          if (sortMethod) {
            if (option.name === sortMethod) {
              sortFunction = option.sort;
              break;
            }
          } else if (sampleItems.every(option.pattern)) {
            sortFunction = option.sort;
            break;
          }
        }

        this.col = column;
        for (const tbody of this.table.tBodies) {
          if (tbody.rows.length < 2) continue;
          const newRows = [], noSorts = {};
          let totalRows = 0;

          for (let j = 0; j < tbody.rows.length; j++) {
            const row = tbody.rows[j];
            if (row.getAttribute("data-sort-method") === "none") {
              noSorts[totalRows] = row;
            } else {
              const cell = columnKey ? getCellByKey(row.cells, columnKey) : row.cells[this.col];
              newRows.push({ tr: row, td: cell ? getInnerText(cell) : "", index: totalRows });
            }
            totalRows++;
          }

          if (sortOrder === "descending") {
            newRows.sort(stabilize(sortFunction, false)).reverse();
          } else {
            newRows.sort(stabilize(sortFunction, true));
          }

          let noSortsSoFar = 0;
          for (let j = 0; j < totalRows; j++) {
            const item = noSorts[j] || newRows[j - noSortsSoFar].tr;
            if (noSorts[j]) noSortsSoFar++;
            tbody.appendChild(item);
          }
        }

        this.table.dispatchEvent(createEvent("afterSort"));
      });
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
