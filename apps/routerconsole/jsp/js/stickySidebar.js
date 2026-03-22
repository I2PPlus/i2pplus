/**
 * @module stickySidebar
 * @description Makes the sidebar stick to the top of the viewport when the
 * sidebar content is shorter than the viewport height. Dynamically calculates
 * and applies sticky positioning.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

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
   * @returns {void}
   */
  function calcHeight() {
    const sbHeight = sb.getBoundingClientRect().height;
    const viewportHeight = window.visualViewport ? window.visualViewport.height : window.innerHeight;
    if (sbHeight + 5 < viewportHeight) {
      sbWrap.style.position = "sticky";
      sbWrap.style.top = "5px";
      sbWrap.classList.add("sticky");
    } else {
      sbWrap.style.position = "";
      sbWrap.style.top = "";
      sbWrap.classList.remove("sticky");
    }
  }

  calcHeight();

  sbWrap.addEventListener("click", (event) => {
    if (event.target.classList.contains("toggleSection")) { setTimeout(calcHeight, 500); }
  });

  window.addEventListener("resize", calcHeight);
}

export { stickySidebar };
