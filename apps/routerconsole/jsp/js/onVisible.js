function onVisible(element, callback) {
  if (!element || !(element instanceof Element)) {return;}

  new IntersectionObserver((entries, observer) => {
    entries.forEach(entry => {
      if (entry.intersectionRatio > 0 && !document.hidden) {
        callback(element);
        observer.disconnect();
      }
    });
  }).observe(element);
}

function onHidden(element, callback) {
  if (!element || !(element instanceof Element)) {return;}

  new IntersectionObserver((entries, observer) => {
    entries.forEach(entry => {
      if (entry.intersectionRatio === 0 && !document.hidden) {
        callback(element);
      }
    });
  }).unobserve(element);
};

export {onVisible, onHidden};
