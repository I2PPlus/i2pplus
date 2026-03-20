/**
 * @module onVisible
 * @description Provides utility functions to execute callbacks when DOM elements
 * become visible or hidden using IntersectionObserver and document visibility tracking.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

/** @type {boolean} */
let isDocumentVisible = true;
/** @type {boolean} */
let listenerAdded = false;

/**
 * Adds a one-time visibility change listener to the document.
 * @function addListener
 * @returns {void}
 */
function addListener() {
  if (!listenerAdded) {
    document.addEventListener("visibilitychange", () => {
      isDocumentVisible = !document.hidden;
    });
    listenerAdded = true;
  }
}

/**
 * Executes the callback once when the element becomes visible in the viewport
 * and the document is visible.
 * @function onVisible
 * @param {Element} element - The DOM element to observe
 * @param {Function} callback - The function to call when visible, receives the element
 * @returns {void}
 */
function onVisible(element, callback) {
  if (!element || !(element instanceof Element)) { return; }
  addListener();
  new IntersectionObserver((entries, observer) => {
    entries.forEach(entry => {
      if (entry.intersectionRatio > 0 && isDocumentVisible) {
        callback(isDocumentVisible ? element : null);
        observer.disconnect();
      }
    });
  }).observe(element);
}

/**
 * Executes the callback once when the element becomes hidden (leaves viewport)
 * while the document is visible.
 * @function onHidden
 * @param {Element} element - The DOM element to observe
 * @param {Function} callback - The function to call when hidden, receives the element
 * @returns {void}
 */
function onHidden(element, callback) {
  if (!element || !(element instanceof Element)) { return; }
  addListener();
  const observer = new IntersectionObserver((entries, observer) => {
    entries.forEach(entry => {
      if (entry.intersectionRatio === 0 && isDocumentVisible) {
        callback(isDocumentVisible ? element : null);
        observer.disconnect();
      }
    });
  });
  observer.observe(element);
}

export {onVisible, onHidden};