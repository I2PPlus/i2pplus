/**
 * @module refreshOnClick
 * @description Refreshes specified DOM elements via AJAX when trigger selectors
 * are clicked. Supports multiple refresh attempts with configurable intervals
 * and progress indicators.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Sets up click-triggered AJAX refresh for specified DOM elements.
 * @function refreshOnClick
 * @param {string} triggerSelectors - Comma-separated CSS selectors that trigger refresh on click
 * @param {string} updateSelectors - Comma-separated CSS selectors of elements to update
 * @param {number} [interval=500] - Interval in milliseconds between refresh attempts
 * @param {number} [maxAttempts=4] - Maximum number of refresh attempts per click
 * @returns {void}
 * @example refreshOnClick(".refresh-btn", "#status, #stats", 1000, 3)
 */
function refreshOnClick(triggerSelectors, updateSelectors, interval = 500, maxAttempts = 4) {
  const updateElements = updateSelectors.split(", ").map(s => {
    const el = document.querySelector(s.trim());
    return el ? { el, sel: s } : null;
  }).filter(Boolean);

  const triggers = triggerSelectors.split(", ").map(s => s.trim());
  if (!updateElements.length || !triggers.length) return;

  const doRefresh = async () => {
    try {
      const res = await fetch(window.location.href);
      const text = await res.text();
      const doc = new DOMParser().parseFromString(text, "text/html");

      updateElements.forEach(({ el, sel }) => {
        const newEl = doc.querySelector(sel);
        if (newEl) el.innerHTML = newEl.innerHTML;
      });
    } catch (e) { progressx.hide(); }
  };

  document.documentElement.addEventListener("click", e => {
    const target = e.target;
    if (!triggers.some(sel => target.matches(sel))) return;

    let attempts = 0;
    target.style.pointerEvents = "none";
    target.style.opacity = ".5";

    const timer = setInterval(() => {
      if (attempts >= maxAttempts) {
        clearInterval(timer);
        target.style.pointerEvents = "";
        target.style.opacity = "";
        progressx.hide();
        return;
      }

      if (attempts === 0) progressx.show(theme);
      doRefresh();
      attempts++;
    }, interval);

    doRefresh();
  });
}