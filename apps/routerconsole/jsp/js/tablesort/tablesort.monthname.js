/**

 * @description Month name sorting plugin for Tablesort
 */

(function() {
  /**
   * @type {Array<string>}
   * @description Array of month names for sorting
   */
  const monthNames = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

  /**
   * @description Extend Tablesort with month name sorting
   */
  Tablesort.extend(
    'monthname',
    item => /January|February|March|April|May|June|July|August|September|October|November|December/i.test(item),
    (a, b) => monthNames.indexOf(b) - monthNames.indexOf(a)
  );
})();