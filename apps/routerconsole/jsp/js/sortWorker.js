/**
 * @module sortWorker
 * @description Handles table sorting in a background worker for large tables.
 * Supports number, date, and string column types with ascending/descending direction.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

/**
 * Receives sort data from the main thread, sorts rows, and posts results back.
 * @function self.onmessage
 * @param {MessageEvent} e - The message event containing rows, sortColumn, direction, and columnType
 * @returns {void}
 */
self.onmessage = function(e) {
  const { rows, sortColumn, direction, columnType } = e.data;

  const sorted = sortRows([...rows], sortColumn, direction, columnType);

  self.postMessage({ sorted });
};

/**
 * Sorts an array of row objects by the specified column and direction.
 * @function sortRows
 * @param {Array<Object<string, string>>} rows - The rows to sort
 * @param {string} sortColumn - The column key to sort by
 * @param {string} direction - Sort direction ("ascending" or "descending")
 * @param {string} columnType - The data type ("number", "date", or "string")
 * @returns {Array<Object>} The sorted rows array
 */
function sortRows(rows, sortColumn, direction, columnType) {
  const multiplier = direction === "descending" ? -1 : 1;

  rows.sort((a, b) => {
    let valA = a[sortColumn];
    let valB = b[sortColumn];

    if (columnType === "number") {
      valA = parseFloat(valA) || 0;
      valB = parseFloat(valB) || 0;
      return (valA - valB) * multiplier;
    }

    if (columnType === "date") {
      valA = new Date(valA).getTime() || 0;
      valB = new Date(valB).getTime() || 0;
      return (valA - valB) * multiplier;
    }

    valA = String(valA).toLowerCase();
    valB = String(valB).toLowerCase();

    if (valA < valB) { return -1 * multiplier; }
    if (valA > valB) { return 1 * multiplier; }
    return 0;
  });

  return rows;
}
