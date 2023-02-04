function onVisible(element, callback) {
  new IntersectionObserver((entries, observer) => {
    entries.forEach(entry => {
      if (entry.intersectionRatio > 0) {
        callback(element);
        observer.disconnect();
      } else {
        if (timerId !== null) {clearInterval(timerId);}
        if (refreshId !== null) {clearInterval(refreshId);}
      }
    });
  }).observe(element);
}

export {onVisible};