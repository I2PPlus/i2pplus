/* I2P+ clickToClose.js by dr|z3d */
/* Remove an element from the DOM onClick */
/* License: AGPL3 or later */

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

function setupClickToClose(selectors) {
  const container = document.body;
  container.addEventListener("click", (event) => {
    const element = event.target.closest(selectors);
    if (element) { clickToClose(element); }
  });
}

document.addEventListener("DOMContentLoaded", () => { setupClickToClose(".canClose"); });