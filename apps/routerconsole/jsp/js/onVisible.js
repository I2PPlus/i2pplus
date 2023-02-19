function onVisible(element, callback) {
  new IntersectionObserver((entries, observer) => {
    entries.forEach(entry => {
      if (entry.intersectionRatio > 0) {
        callback(element);
        observer.disconnect();
      } else {
        if (typeof timerId !== "null" && typeof timerId !== "undefined") {clearInterval(timerId);}
        if (typeof refreshId !== "null" && typeof refreshId !== "undefined") {clearInterval(refreshId);}
      }
    });
  }).observe(element);
}

export {onVisible};