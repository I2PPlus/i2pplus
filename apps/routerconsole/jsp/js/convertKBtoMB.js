/* I2P+ convertKBtoMB.js by dr|z3d */
/* Converts KB to MB when > 1024KB */
/* License: AGPL3 or later */

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