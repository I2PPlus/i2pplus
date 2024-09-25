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

  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (entry.intersectionRatio > 0) {
        entry.target.classList.add("lazyshow");
        entry.target.classList.remove("lazyhide");
        observer.unobserve(entry.target);
      } else {
        entry.target.classList.remove("lazyshow");
        entry.target.classList.add("lazyhide");
      }
    });
  });

  function debounce(fn, delay) {
    let timeoutId;
    return function() {
      clearTimeout(timeoutId);
      timeoutId = setTimeout(() => {
        fn.apply(this, arguments);
      }, delay);
    };
  }

  const lazyload = debounce(() => {
    const lazyelements = document.querySelectorAll(".lazy");
    lazyelements.forEach(lazyElement => {
      const parentElement = lazyElement.closest(parentSelector) || document.documentElement;
      observer.observe(lazyElement, {
        root: parentElement,
      });
    });
  }, 180);

  document.addEventListener("DOMContentLoaded", () => {
    window.addEventListener("scroll", lazyload);
  });

})();