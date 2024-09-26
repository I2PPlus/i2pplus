/* I2P+ lazyload.js by dr|z3d */
/* Show and hide tagged elements as they enter and exit the viewport */
/* License: AGPL3 or later */

/* I2P+ lazyload.js by dr|z3d */
/* Show and hide tagged elements as they enter and exit the viewport */
/* License: AGPL3 or later */

(function() {
  const parentSelectors = [
    ".leasesets_container",
    ".main",
    ".tunneldisplay",
    "#ffProfiles",
    "#host_list",
    "#profilelist"
  ];

  const parentSelector = parentSelectors.join(", ");
  const lazyElementsSet = new Set();
  const main = document.querySelector(".main") || document.querySelector("#page");

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
  }, { root: null, rootMargin: "10px", threshold: 0 });

  function debounce(fn, delay) {
    let timeoutId;
    return function() {
      clearTimeout(timeoutId);
      timeoutId = setTimeout(() => {
        fn.apply(this, arguments);
      }, delay);
    };
  }

  function lazyload() {
    const lazyElements = document.querySelectorAll(".lazy");
    lazyElements.forEach(lazyElement => {
      if (!lazyElementsSet.has(lazyElement)) {
        const parentElement = lazyElement.closest(parentSelector) || document.documentElement;
        observer.observe(lazyElement);
        lazyElementsSet.add(lazyElement);
      }
    });
  }

  const debouncedLazyLoad = debounce(() => {
    if ("requestIdleCallback" in window) {requestIdleCallback(lazyload);}
    else {requestAnimationFrame(lazyload);}
  }, 180);

  function checkView() {lazyload();}

  document.addEventListener("DOMContentLoaded", () => {
    checkView();
    window.addEventListener("scroll", debouncedLazyLoad);
    window.addEventListener("resize", debouncedLazyLoad);
  });

})();