/**
 * @module sortWorker
 * @description Handles table sorting in a background worker for all tables.
 * Comparison functions are shared with the main thread via sortShared.js.
 * Empty cells always sort to the bottom.
 * Based on tristen/tablesort (MIT) https://github.com/tristen/tablesort;
 * worker implementation by dr|z3d for I2P+ (AGPLv3).
 * @license AGPLv3 or later
 */

importScripts("/js/tablesort/sortShared.js");

/**
 * Receives sort data from the main thread, sorts rows, and posts results back.
 * @param {MessageEvent} e
 */
self.onmessage = function(e) {
  const { rows, sortColumn, direction, columnType } = e.data;
  self.postMessage({ sorted: sortRows([...rows], sortColumn, direction, columnType) });
};

/**
 * @param {Array<Object<string, string>>} rows
 * @param {string} sortColumn
 * @param {string} direction - "ascending" or "descending"
 * @param {string} columnType - Sort type key
 * @returns {Array<Object>}
 */
function sortRows(rows, sortColumn, direction, columnType) {
  const multiplier = direction === "descending" ? -1 : 1;

  rows.sort((a, b) => {
    const valA = a[sortColumn], valB = b[sortColumn];
    let res;

    switch (columnType) {
      case "number":    res = numberCmpEL(valA, valB); break;
      case "date":      res = dateCmpEL(valA, valB); break;
      case "natural":   res = naturalCmpEL(valA, valB); break;
      case "dotsep":    res = dotsepCmpEL(valA, valB); break;
      case "filesize":  res = filesizeCmpEL(valA, valB); break;
      case "monthname": res = monthnameCmpEL(valA, valB); break;
      case "intl":      res = intlCmpEL(valA, valB); break;
      default:          res = stringCmpEL(valA, valB); break;
    }

    return res * multiplier;
  });

  return rows;
}
