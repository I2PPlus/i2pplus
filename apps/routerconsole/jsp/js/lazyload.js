/**
 * @module lazyload
 * @description Implements lazy loading for elements with the "lazy" CSS class.
 * Uses IntersectionObserver to toggle "lazyshow"/"lazyhide" classes as elements
 * enter and exit the viewport. Falls back to simple class removal for small sets.
 * @author dr|z3d
 * @license AGPL3 or later
 */

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

  /**
   * Finds all elements with the "lazy" class and either removes the class directly
   * (for < 10 elements) or sets up IntersectionObserver for larger sets.
   * @function lazyload
   * @returns {void}
   */
  const lazyload = () => {
    const lazyElements = document.querySelectorAll(".lazy");
    if (lazyElements.length === 0) { return; }

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