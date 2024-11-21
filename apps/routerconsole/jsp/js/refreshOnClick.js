/* I2P+ refreshOnClick.js by dr|z3d */
/* Refresh defined elements via ajax when defined selectors are clicked */
/* License: AGPL3 or later */

function refreshOnClick(triggerSelectors, updateSelectors, interval = 500, maxAttempts = 4) {
  const updateElements = updateSelectors.split(", ").map(selector => ({
    element: document.querySelector(selector),
    selector
  })).filter(({ element }) => element);

  const triggers = triggerSelectors.split(", ").map(s => s.trim());
  if (!updateElements.length || !triggers.length) return;

  const doRefresh = async () => {
    try {
      const doc = new DOMParser().parseFromString(await (await fetch(window.location.href)).text(), "text/html");
      updateElements.forEach(({ element, selector }) => {
        const newContent = doc.querySelector(selector);
        if (newContent) {element.innerHTML = newContent.innerHTML;}
      });
    } catch (error) {progressx.hide();}
  };

  document.documentElement.addEventListener("click", (event) => {
    if (triggers.some(selector => event.target.matches(selector))) {
      let attempts = 0;
      Object.assign(event.target.style, { pointerEvents: "none", opacity: ".5" });
      const refreshInterval = setInterval(() => {
        if (attempts++ >= maxAttempts) clearInterval(refreshInterval);
        else doRefresh();
        if (attempts < 1) {progressx.show(theme);}
        else {progressx.hide();}
      }, interval);
      doRefresh();
    }
  });
}