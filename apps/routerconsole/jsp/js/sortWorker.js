/* I2P+ sortWorker.js br dr|z3d */
/* Handle table sorting in tablesort.js in a background worker for large tables */
/* License: AGPLv3 or later */

self.onmessage = function(e) {
  const { rows, sortColumn, direction, columnType } = e.data;

  const sorted = sortRows([...rows], sortColumn, direction, columnType);

  self.postMessage({ sorted });
};

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

    if (valA < valB) return -1 * multiplier;
    if (valA > valB) return 1 * multiplier;
    return 0;
  });

  return rows;
}
