/**
 * @file tablesort.number.js
 * @description Number sorting plugin for Tablesort
 */

(function () {
  /**
   * @function cleanNumber
   * @description Cleans numeric strings without breaking scientific notation or decimals
   * @param {string} val - The value to clean
   * @returns {string} The cleaned numeric string
   */
  const cleanNumber = (val) => {
    if (typeof val !== 'string') return '';
    // Allow digits, minus, dot, E/e for scientific notation, and %
    return val.replace(/[^-0-9.Ee%]/g, '');
  };

  /**
   * @function compareNumber
   * @description Compares two numeric values, handling NaN gracefully
   * @param {string} a - The first value to compare
   * @param {string} b - The second value to compare
   * @returns {number} Negative if b < a, positive if b > a, 0 if equal
   */
  const compareNumber = (a, b) => {
    const numA = parseFloat(a);
    const numB = parseFloat(b);

    if (isNaN(numA) && isNaN(numB)) return 0;
    if (isNaN(numA)) return 1;  // b comes first
    if (isNaN(numB)) return -1; // a comes first
    return numB - numA;         // descending sort
  };

  // Register the numeric sorter with Tablesort
  Tablesort.extend('number',
    // Pattern: detects strings that contain valid numbers
    function (item) {
      const cleaned = cleanNumber(item);
      return cleaned !== '' && !isNaN(parseFloat(cleaned)) && isFinite(cleaned);
    },
    // Sort function: compares cleaned numeric values
    function (a, b) {
      return compareNumber(cleanNumber(a), cleanNumber(b));
    }
  );
})();
