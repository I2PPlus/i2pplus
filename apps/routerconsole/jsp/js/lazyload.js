const lazyload = () => {
  const lazyelements = document.querySelectorAll(".lazy");
  const parentSelectors = [
    ".leasesets_container",
    ".tunneldisplay",
    "#ffProfiles",
    "#host_list",
    ".main",
    "#profilelist"
  ];
  let parentElement;

  lazyelements.forEach(lazyElement => {
    for (let i = 0; i < parentSelectors.length; i++) {
      parentElement = lazyElement.closest(parentSelectors[i]);

      if (parentElement) {
        break;
      }
    }

    if (!parentElement || parentElement.nodeType !== Node.ELEMENT_NODE) {
      parentElement = document.documentElement;
    }

    let observer = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (entry.intersectionRatio > 0) {
          entry.target.classList.add("lazyshow");
          entry.target.classList.remove("lazyhide");
        } else {
          entry.target.classList.remove("lazyshow");
          entry.target.classList.add("lazyhide");
        }
      });
    });

    observer.observe(lazyElement, {
      root: parentElement
    });
  });
};

window.requestAnimationFrame(lazyload);