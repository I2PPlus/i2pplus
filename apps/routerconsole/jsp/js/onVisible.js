let isDocumentVisible = true;

document.addEventListener("visibilitychange", () => {
  isDocumentVisible = !document.hidden;
});

function onVisible(element, callback) {
  if (!element || !(element instanceof Element)) { return; }

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