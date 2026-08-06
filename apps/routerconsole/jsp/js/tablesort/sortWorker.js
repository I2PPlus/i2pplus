/**
 * @module sortWorker
 * @description Handles table sorting in a background worker for all tables.
 * Comparison functions and the sort driver are shared with the main thread via
 * sortShared.js. Empty cells sort to the bottom in ascending order; the caller
 * inverts direction for descending.
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
