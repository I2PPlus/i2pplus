/**
 * @module lazyload
 * @description Lazy loading for elements with the "lazy" CSS class.
 * Toggles lazyshow/lazyhide classes via IntersectionObserver as elements enter
 * or leave the viewport. Falls back to class removal for small sets.
 * @author dr|z3d
 * @license AGPL3 or later
 */

(function initLazyload() {
  let lazyElementsSet = new Set();
  let observer = null;

  const initObserver = () => new IntersectionObserver(entries => {
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

  const body = document.body;

  const lazyload = () => {
    const lazyElements = document.querySelectorAll(".lazy");
    if (lazyElements.length === 0) { return; }

    if (observer) observer.disconnect();
    lazyElementsSet = new Set();
    observer = initObserver();

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

  document.addEventListener("afterSort", lazyload);
})();
