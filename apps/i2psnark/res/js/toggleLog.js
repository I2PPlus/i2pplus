/* toggleLog.js by dr|3d */
/* Enable toggling height of I2PSnark's screenlog */
/* License: AGPL3 or later */

function initToggleLog() {

  const screenlog = document.getElementById("screenlog");
  const messages = document.getElementById("messages");
  const toggleLogCss = document.getElementById("toggleLogCss");
  const sh = document.getElementById("shrink");
  const ex = document.getElementById("expand");
  const expandLog = document.getElementById("expandLog");
  const shrinkLog = document.getElementById("shrinkLog");
  const isExpanded = localStorage.getItem("screenlog") === "expanded";

  const expandCss = "#screenlog.xpanded{height:auto!important;max-height:300px!important;min-height:56px;" +
                    "transition:max-height .1s linear,min-height .1s linear;will-change:transform}" +
                    "#screenlog:hover,#screenlog:focus{overflow-y:auto}" +
                    "#expand{display:none}" +
                    "#shrink:not([hidden]){display:inline-block}" +
                    "@media (min-width:1500px){#screenlog.xpanded{min-height:60px!important}}";

  const collapseCss = "#screenlog.collapsed{max-height:56px!important;min-height:56px}" +
                      "#shrink{display:none}" +
                      "#expand:not([hidden]){display:inline-block}" +
                      "@media (min-width:1500px){#screenlog.collapsed{height:60px!important;min-height:60px}}";

  function clean() {
    if (screenlog?.classList.contains("xpanded")) {screenlog.classList.remove("xpanded");}
    if (screenlog?.classList.contains("collapsed")) {screenlog.classList.remove("collapsed");}
    if (expandLog) {expandLog.remove();}
    if (shrinkLog) {shrinkLog.remove();}
  }

  function expand() {
    requestAnimationFrame(() => {
      clean();
      if (toggleLogCss) {
        toggleLogCss.innerHTML = expandCss;
      }
      screenlog?.classList.add("xpanded");
      localStorage.setItem("screenlog", "expanded");
      ex?.removeEventListener("click", expand);
      sh?.addEventListener("click", shrink);
    });
  }

  function shrink() {
    requestAnimationFrame(() => {
      clean();
      if (toggleLogCss) {
        toggleLogCss.innerHTML = collapseCss;
      }
      screenlog?.classList.add("collapsed");
      localStorage.setItem("screenlog", "collapsed");
      sh?.removeEventListener("click", shrink);
      ex?.addEventListener("click", expand);
    });
  }

  if (!localStorage.getItem("screenlog")) {
    localStorage.setItem("screenlog", "collapsed");
  }

  if (screenlog) {
    if (localStorage.getItem("screenlog")) {
      [shrink, expand][localStorage.getItem("screenlog") === "expanded" ? 1 : 0]();
      if (sh) {sh.removeAttribute("hidden");}
    } else {
      shrink();
      if (ex) {ex.removeAttribute("hidden");}
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    if (sh && isExpanded) {sh.addEventListener("click", shrink);}
    else if (ex) {ex.addEventListener("click", expand);}
    if (ex) {ex.removeAttribute("hidden");}
    if (sh) {sh.removeAttribute("hidden");}
  });

}

export {initToggleLog};