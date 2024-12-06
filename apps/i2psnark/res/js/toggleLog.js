/* toggleLog.js for I2PSnark by dr|3d */
/* Enable toggling height of I2PSnark's screenlog */
/* License: AGPL3 or later */

let isScreenlogExpanded = false;

function initToggleLog() {
  const screenlog = document.getElementById("screenlog");
  const toggleLogCss = document.getElementById("toggleLogCss");
  const exCss = "#screenlog.xpanded{height:auto!important;max-height:300px!important;min-height:56px;will-change:transform}" +
                "#screenlog:hover,#screenlog:focus{overflow-y:auto}" +
                "@media (min-width:1500px){#screenlog.xpanded{min-height:60px!important}}";
  const shCss = "#screenlog.collapsed{max-height:56px!important;min-height:56px}" +
                "@media (min-width:1500px){#screenlog.collapsed{height:60px!important;min-height:60px}}";

  if (!screenlog) return;

  function clean() {
    screenlog?.classList.remove("xpanded", "collapsed");
    [...document.querySelectorAll("#expandLog, #shrinkLog")].forEach(el => el?.remove());
  }

  document.documentElement.addEventListener("click", e => {
    const target = e.target.closest("#expand, #shrink");
    if (!target) return;
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
  document.querySelector("#expand").hidden = false;
}

export { initToggleLog };