/**
 * @module toggleLog
 * @file toggleLog.js - Enable toggling of I2PSnark's screen log height.
 * @description Provides expand/collapse controls for the I2PSnark screen log (#screenlog).
 * Adds "expand" and "shrink" buttons that toggle between a collapsed (56px) and expanded
 * (up to 300px) view. Injects the required CSS dynamically.
 * @author dr|3d
 * @license AGPL3 or later
 */

/**
 * @type {boolean}
 * @description Whether the screen log is currently in expanded state.
 */
let isScreenlogExpanded = false;

/**
 * @function initToggleLog
 * @description Initializes the screen log toggle functionality. Creates expand/shrink
 * buttons, injects CSS for expanded and collapsed states, and registers click handlers
 * to toggle between states. Cleans up any existing elements to prevent duplicates.
 * @returns {void}
 */
function initToggleLog() {
  const screenlog = document.getElementById("screenlog");
  const toggleLogCss = document.getElementById("toggleLogCss");
  const exCss = "#screenlog.xpanded{height:auto!important;max-height:300px!important;min-height:56px;will-change:transform}" +
                "#screenlog:hover,#screenlog:focus{overflow-y:auto}" +
                "@media (min-width:1500px){#screenlog.xpanded{min-height:60px!important}}";
  const shCss = "#screenlog.collapsed{max-height:56px!important;min-height:56px}" +
                "@media (min-width:1500px){#screenlog.collapsed{height:60px!important;min-height:60px}}";

  if (!screenlog) { return; }

  /**
   * @function clean
   * @description Removes existing expand/shrink CSS classes and button elements to
   * prepare for reinitialization.
   * @returns {void}
   */
  function clean() {
    screenlog?.classList.remove("xpanded", "collapsed");
    [...document.querySelectorAll("#expandLog, #shrinkLog")].forEach(el => el?.remove());
  }

  document.documentElement.addEventListener("click", e => {
    const target = e.target.closest("#expand, #shrink");
    if (!target) { return; }
    requestAnimationFrame(() => {
      clean();
      toggleLogCss.innerHTML = target.id === "expand" ? exCss : shCss;
      screenlog.classList.toggle("xpanded", target.id === "expand");
      screenlog.classList.toggle("collapsed", target.id === "shrink");
      isScreenlogExpanded = target.id === "expand";
      document.querySelector("#expand").hidden = isScreenlogExpanded;
      document.querySelector("#shrink").hidden = !isScreenlogExpanded;
    });
  });

  clean();
  toggleLogCss.innerHTML = shCss;
  screenlog.classList.add("collapsed");
  const expand = document.getElementById("expand");
  if (expand) {expand.hidden = false;}
}

document.addEventListener("DOMContentLoaded", initToggleLog);