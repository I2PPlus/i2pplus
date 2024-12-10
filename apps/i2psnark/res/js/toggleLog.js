/* toggleLog.js for I2PSnark by dr|3d */
/* Enable toggling height of I2PSnark's screenlog */
/* License: AGPL3 or later */

(() => {
  let isScreenlogExpanded = false;

  function initToggleLog() {
    const screenlog = document.getElementById("screenlog");
    const toggleLogCss = document.getElementById("toggleLogCss");
    const exCss = "#screenlog.xpanded{height:auto!important;max-height:300px!important;min-height:56px;will-change:transform}" +
                  "#screenlog:hover,#screenlog:focus{overflow-y:auto}" +
                  "@media (min-width:1500px){#screenlog.xpanded{min-height:60px!important}}";
    const shCss = "#screenlog.collapsed{max-height:56px!important;min-height:56px}" +
                  "@media (min-width:1500px){#screenlog.collapsed{height:60px!important;min-height:60px}}";
    const expand = document.getElementById("expand");
    const collapse = document.getElementById("collapse");

    if (!screenlog) return;

    function clean() {
      screenlog?.classList.remove("xpanded", "collapsed");
      [...document.querySelectorAll("#expandLog, #shrinkLog")].forEach(el => el?.remove());
    }

    document.documentElement.addEventListener("click", e => {
      const target = e.target.closest("#expand, #shrink");
      if (!target || !expand || !collapse) return;
      requestAnimationFrame(() => {
        clean();
        toggleLogCss.innerHTML = target.id === "expand" ? exCss : shCss;
        screenlog.classList.toggle("xpanded", target.id === "expand");
        screenlog.classList.toggle("collapsed", target.id === "shrink");
        isScreenlogExpanded = target.id === "expand";
        if (expand) {expand.hidden = isScreenlogExpanded;}
        if (collapse) {collapse.hidden = !isScreenlogExpanded;}
      });
    });

    clean();
    toggleLogCss.innerHTML = shCss;
    screenlog.classList.add("collapsed");
    if (expand) {expand.hidden = false;}
  }

  initToggleLog();
})();