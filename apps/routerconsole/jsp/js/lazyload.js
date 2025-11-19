/* I2P+ lazyload.js by dr|z3d */
/* Show and hide tagged elements as they enter and exit the viewport */
/* License: AGPL3 or later */

(function initLazyload() {
  const lazyElementsSet = new Set();

  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      const lazyElement = entry.target;
      if (entry.isIntersecting) {
        lazyElement.classList.add("lazyshow");
        lazyElement.classList.remove("lazyhide");
      } else {
        lazyElement.classList.remove("lazyshow");
        lazyElement.classList.add("lazyhide");
      }
    });
  }, { rootMargin: "100px 0px 100px 0px", threshold: 0.1 });

  const doc = document.documentElement;
  const body = document.body;

  const lazyload = () => {
    const lazyElements = document.querySelectorAll(".lazy");
    if (lazyElements.length === 0) return;

    if (lazyElements.length < 10) {
      for (let i = 0; i < lazyElements.length; i++) {
        lazyElements[i].classList.remove("lazy");
      }
    } else {
      for (let i = 0; i < lazyElements.length; i++) {
        const lazyElement = lazyElements[i];
        if (!lazyElementsSet.has(lazyElement)) {
          observer.observe(lazyElement);
          lazyElementsSet.add(lazyElement);
        }
      }
    }
  };

  document.addEventListener("DOMContentLoaded", () => {
    body.classList.add("ready", "loaded");
    lazyload();
  });
})();