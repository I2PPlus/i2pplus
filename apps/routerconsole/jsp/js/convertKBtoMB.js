/**
 * @module convertKBtoMB
 * @description Converts displayed KB values to MB when they exceed 1024KB,
 * applied to elements matching the given CSS selectors or element array.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Converts KB text values to MB in matching DOM elements when value exceeds 1024KB.
 * @function convertKBtoMB
 * @param {string|string[]|Element[]} selector - CSS selector string, array of selectors, or array of elements
 * @returns {void}
 * @example convertKBtoMB(".bandwidth-cell")
 * @example convertKBtoMB(["#total-in", "#total-out"])
 */
export function convertKBtoMB(selector) {
  let selectors, elements;

  if (typeof selector === "string") {selectors = [selector];}
  else if (Array.isArray(selector) && typeof selector[0] === "string") {selectors = selector;}
  else if (Array.isArray(selector) && selector[0] instanceof Element) {elements = selector;}
  else {return;}

  if (selectors) {
    elements = selectors.flatMap(selector => Array.from(document.querySelectorAll(selector)));
  }

  elements?.forEach(element => {
    const text = element.textContent;
    const match = text.match(/(\d+(\.\d+)?)\s*KB/i);
    if (match) {
      const kbValue = parseFloat(match[1]);
      if (kbValue > 1024) {
        const mbValue = kbValue / 1024;
        element.textContent = text.replace(match[0], mbValue.toFixed(1) + "MB");
      }
    }
  });
}