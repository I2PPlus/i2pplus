function Tablesort(el, options) {
  if (!(this instanceof Tablesort)) return new Tablesort(el, options);
  if (!el || el.tagName !== "TABLE") throw new Error("Element must be a table");
  this.init(el, options || {});
}

const sortOptions = [];

const createEvent = (name) =>
  typeof CustomEvent === "function" ? new CustomEvent(name) : (() => {
    const evt = document.createEvent("CustomEvent");
    evt.initCustomEvent(name, false, false, undefined);
    return evt;
  })();

const getInnerText = (el) => el.getAttribute("data-sort") ?? el.textContent ?? el.innerText ?? "";

const caseInsensitiveSort = (a, b) => {
  a = a.trim().toLowerCase(); b = b.trim().toLowerCase();
  return a === b ? 0 : (a < b ? 1 : -1);
};

const getCellByKey = (cells, key) => Array.from(cells).find(cell => cell.getAttribute("data-sort-column-key") === key);

const stabilize = (sort, antiStabilize) => (a, b) => {
  const res = sort(a.td, b.td);
  return res === 0 ? (antiStabilize ? b.index - a.index : a.index - a.index) : res;
};

Tablesort.extend = (name, pattern, sort) => {
  if (typeof pattern !== "function" || typeof sort !== "function")
    throw new Error("Pattern and sort must be functions");
  sortOptions.push({ name, pattern, sort });
};

Tablesort.prototype = {
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
        cell.tabIndex = 0; cell.addEventListener("click", onClick);
        if (cell.hasAttribute("data-sort-default")) this.current = cell;
      }
    }
    if (this.current) this.sortTable(this.current);
  },

  sortTable(header, update) {
    const columnKey = header.getAttribute("data-sort-column-key"), column = header.cellIndex;
    let sortFunction = caseInsensitiveSort, sortMethod = header.getAttribute("data-sort-method"), sortOrder = header.getAttribute("aria-sort");

    this.table.dispatchEvent(createEvent("beforeSort"));
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
          newRows.sort(stabilize(sortFunction, true));
        } else {
          newRows.sort(stabilize(sortFunction, false)).reverse();
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

  refresh() {
    if (this.current) this.sortTable(this.current, true);
  }
};

if (typeof module !== "undefined" && module.exports) module.exports = Tablesort;
window.Tablesort = Tablesort;

export default Tablesort;
