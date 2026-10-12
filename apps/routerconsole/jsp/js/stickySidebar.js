/**
 * @module stickySidebar
 * @description Makes the sidebar stick to the top of the viewport when the
 * sidebar content is shorter than the viewport height. Dynamically calculates
 * and applies sticky positioning, and keeps the clicked section in view when an
 * expansion costs the sidebar its sticky status.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

/** @type {EventListener|null} */
let toggleHandler = null;
/** @type {EventListener|null} */
let resizeHandler = null;

/**
 * Calculates sidebar and viewport heights and applies/removes sticky positioning.
 * @function stickySidebar
 * @returns {void}
 */
function stickySidebar() {
  if (stickySidebarEnabled === false) { return; }
  const sbWrap = document.getElementById("sb_wrap");
  const sb = document.getElementById("sidebar");
  if (!sbWrap || !sb) { return; }

  /**
   * Compares sidebar height to viewport and toggles sticky CSS accordingly.
   * @function calcHeight
   * @returns {boolean} true if the sidebar is sticky after this call
   */
  function calcHeight() {
    const sbHeight = sb.getBoundingClientRect().height;
    const viewportHeight = window.visualViewport ? window.visualViewport.height : window.innerHeight;
    if (sbHeight + 5 < viewportHeight) {
      sbWrap.style.position = "sticky";
      sbWrap.style.top = "5px";
      sbWrap.classList.add("sticky");
      return true;
    }
    sbWrap.style.position = "";
    sbWrap.style.top = "";
    sbWrap.classList.remove("sticky");
    return false;
  }

  calcHeight();

  /**
   * Re-measures after a section toggle and keeps the toggled section in view.
   *
   * <p>Expanding a section can make the sidebar taller than the viewport, which
   * drops sticky and puts the sidebar back into normal document flow. The
   * sidebar's document position is at the top of the page, so on a long page
   * that yanks the whole sidebar — and the section just opened — off screen.
   * Scrolling the section heading back into view restores it.
   *
   * <p>Only a sticky-to-not-sticky transition scrolls. A sidebar that was never
   * sticky is already where the reader is looking, and scrolling there would
   * move the page for no reason.
   *
   * @function onToggleClick
   * @param {MouseEvent} event - the click that reached the sidebar
   * @returns {void}
   */
  function onToggleClick(event) {
    if (!event.target.classList.contains("toggleSection")) { return; }
    // The toggle is a checkbox inside its section's heading, so the heading is
    // what identifies the section; fall back to the toggle if the markup moves.
    const anchor = event.target.closest("h3") || event.target;
    const wasSticky = sbWrap.classList.contains("sticky");
    setTimeout(() => {
      const sticky = calcHeight();
      if (wasSticky && !sticky) {
        anchor.scrollIntoView({ block: "nearest" });
      }
    }, 500);
  }

  // Exactly one listener of each. The handlers are swapped on every call so
  // their captured element references are the freshest — refreshAll replaces
  // the nodes inside #xhr — while the listener count never grows. sectionToggler
  // calls this on every sidebar refresh, so registering unconditionally here
  // accumulated a duplicate per refresh.
  if (toggleHandler) { sbWrap.removeEventListener("click", toggleHandler); }
  if (resizeHandler) { window.removeEventListener("resize", resizeHandler); }
  toggleHandler = onToggleClick;
  resizeHandler = calcHeight;
  sbWrap.addEventListener("click", toggleHandler);
  window.addEventListener("resize", resizeHandler);
}

export { stickySidebar };
