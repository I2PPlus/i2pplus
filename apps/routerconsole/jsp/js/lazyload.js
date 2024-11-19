/* I2P+ lazyload.js by dr|z3d */
/* Show and hide tagged elements as they enter and exit the viewport */
/* License: AGPL3 or later */

(function initLazyload() {

  const parentSelectors = [ ".leasesets_container", ".main", ".tunneldisplay", "#ffProfiles", "#host_list", "#profilelist" ];
  const parentSelector = parentSelectors.join(", ");
  const lazyElementsSet = new Set();

  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      const lazyElement = entry.target;
      if (entry.isIntersecting) {
        lazyElement.classList.add("lazyshow");
        lazyElement.classList.remove("lazyhide");
        observer.unobserve(lazyElement);
        lazyElementsSet.delete(lazyElement);
      } else {
        lazyElement.classList.remove("lazyshow");
        lazyElement.classList.add("lazyhide");
      }
    });

    if (lazyElementsSet.size === 0) {
      observer.disconnect();
    }
  }, { root: null, rootMargin: "10px", threshold: 0.5 });

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
    lazyElements.forEach(lazyElement => {
      if (!lazyElementsSet.has(lazyElement)) {
        const parentElement = lazyElement.closest(parentSelector) || document.documentElement;
        observer.observe(lazyElement);
        lazyElementsSet.add(lazyElement);
      }
    });
  };

  const throttledLazyLoad = throttle(() => requestAnimationFrame(lazyload), 180);

  document.addEventListener("DOMContentLoaded", () => {
    document.body.classList.add("ready");
    window.addEventListener("scroll", throttledLazyLoad);
    window.addEventListener("resize", throttledLazyLoad);

    window.addEventListener("beforeunload", () => {
      window.removeEventListener("scroll", throttledLazyLoad);
      window.removeEventListener("resize", throttledLazyLoad);
    });
  });

})();