/* I2P+ onVisible.js by dr|z3d */
/* License: AGPLv3 or later */

let isDocumentVisible = true;
let listenerAdded = false;

function addListener() {
  if (!listenerAdded) {
    document.addEventListener("visibilitychange", () => {
      isDocumentVisible = !document.hidden;
    });
    listenerAdded = true;
  }
}

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