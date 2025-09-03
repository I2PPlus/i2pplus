/*
 * I2P+ refreshOnClick.js by dr|z3d
 * Refresh elements via AJAX when selectors are clicked.
 * Usage: refreshOnClick("triggerSelector", "updateSelector", interval, maxAttempts)
 * Requires: progressx.js
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
      catch (e) { progressx.hide(); }
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