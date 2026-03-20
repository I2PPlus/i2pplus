/**
 * @description Internationalized sorting plugin for Tablesort using Intl.Collator
 */

(function(){
  /**
   * @type {Intl.Collator}
   * @description Collator instance for locale-aware string comparison
   */
  const collator = Intl.Collator();

  /**
   * @description Extend Tablesort with internationalized sorting
   */
  Tablesort.extend("intl", _ => false, (a,b) => collator.compare(b, a));
}());