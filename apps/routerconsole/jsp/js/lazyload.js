/* I2P+ lazyload.js by dr|z3d */
/* Show and hide tagged elements as they enter and exit the viewport */
/* License: AGPL3 or later */

(function initLazyload() {

  const parentSelectors = [ ".leasesets_container", ".main", ".tunneldisplay", "#ffProfiles", "#host_list", "#profilelist", "#changelog pre" ];
  const parentSelector = parentSelectors.join(", ");
  const lazyElementsSet = new Set();

  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      const lazyElement = entry.target;
      if (entry.intersectionRatio >= 0.1) {
        lazyElement.classList.add("lazyshow");
        lazyElement.classList.remove("lazyhide");
        observer.unobserve(lazyElement);
        lazyElementsSet.delete(lazyElement);
      } else {
        lazyElement.classList.remove("lazyshow");
        lazyElement.classList.add("lazyhide");
      }
    });
  }, { root: null, rootMargin: "100px 0px 100px 0px", threshold: [0, 0.1, 0.25, 0.5, 0.75, 1] });

  const throttle = (fn, limit) => {
    let inThrottle = false;
    return (...args) => {
      if (!inThrottle) {
        fn.apply(this, args);
        inThrottle = true;
        setTimeout(() => inThrottle = false, limit);
      }
    };
  };

  const lazyload = () => {
    const lazyElements = document.querySelectorAll(".lazy");
    if (lazyElements.length === 0) return;
    if (lazyElements.length < 10) {
      lazyElements.forEach(lazyElement => { lazyElement.classList.remove("lazy"); });
    } else {
      lazyElements.forEach(lazyElement => {
        if (!lazyElementsSet.has(lazyElement)) {
          const parentElement = lazyElement.closest(parentSelector) || document.documentElement;
          observer.observe(lazyElement);
          lazyElementsSet.add(lazyElement);
        }
      });
    }
  };

  const throttledLazyLoad = throttle(() => requestAnimationFrame(lazyload), 50);

  document.addEventListener("DOMContentLoaded", () => {
    const b = document.body;
    b.classList.add("ready");
    window.addEventListener("scroll", throttledLazyLoad, {passive: true});
    window.addEventListener("resize", throttledLazyLoad, {passive: true});

    window.addEventListener("beforeunload", () => {
      window.removeEventListener("scroll", throttledLazyLoad);
      window.removeEventListener("resize", throttledLazyLoad);
    });
  });

})();