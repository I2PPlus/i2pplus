/**
 * @module clickToClose
 * @description Removes an element from the DOM after a fade-out animation on click.
 * Adds a "closed" CSS class, waits for the animation, then removes the element.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Adds the "closed" class to an element and removes it from the DOM after the animation.
 * @function clickToClose
 * @param {HTMLElement} element - The element to close and remove
 * @returns {void}
 */
function clickToClose(element) {
  if (!element || !(element instanceof HTMLElement)) {return;}
  element.classList.add("closed");
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      setTimeout(() => {
        if (element.parentNode) {element.parentNode.removeChild(element);}
      }, 250);
    });
  });
}

/**
 * Sets up a delegated click listener on the document body that closes matching elements.
 * @function setupClickToClose
 * @param {string} selectors - CSS selector string for elements that can be closed
 * @returns {void}
 */
function setupClickToClose(selectors) {
  const container = document.body;
  container.addEventListener("click", (event) => {
    const element = event.target.closest(selectors);
    if (element) { clickToClose(element); }
  });
}

document.addEventListener("DOMContentLoaded", () => { setupClickToClose(".canClose"); });