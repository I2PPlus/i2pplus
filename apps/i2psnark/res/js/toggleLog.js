/* toggleLog.js by dr|3d */
/* Enable toggling height of I2PSnark's screenlog */
/* License: AGPL3 or later */

function initToggleLog() {

  const baseUrl = "/i2psnark/.res/";
  const mainsection = document.getElementById("mainsection");
  const screenlog = document.getElementById("screenlog");
  const toggleLogCss = document.getElementById("toggleLogCss");
  const sh = document.getElementById("shrink");
  const ex = document.getElementById("expand");

  const expandCss = "#screenlog.xpanded{height:auto!important;max-height:300px!important;min-height:56px}" +
                    "#screenlog:hover,#screenlog:focus{overflow-y:auto}" +
                    "#expand{display:none}" +
                    "#shrink{display:inline-block}" +
                    "@media (min-width:1500px){#screenlog.xpanded{min-height:60px!important}}";

  const collapseCss = "#screenlog.collapsed{height:56px!important;min-height:56px}" +
                      "#shrink{display:none}" +
                      "#expand{display:inline-block}" +
                      "@media (min-width:1500px){#screenlog.collapsed{height:60px!important;min-height:60px}}";

  function clean() {
    var expandLog = document.getElementById("expandLog");
    var shrinkLog = document.getElementById("shrinkLog");
    if (screenlog?.classList.contains("xpanded")) {screenlog.classList.remove("xpanded");}
    if (screenlog?.classList.contains("collapsed")) {screenlog.classList.remove("collapsed");}
    if (expandLog) {expandLog.remove();}
    if (shrinkLog) {shrinkLog.remove();}
  }

  function expand() {
    clean();
    if (toggleLogCss) {
      toggleLogCss.innerHTML = expandCss;
    }
    screenlog?.classList.add("xpanded");
    localStorage.setItem("screenlog", "expanded");
    handleShrinkClick();
  }

  function shrink() {
    clean();
    if (toggleLogCss) {
      toggleLogCss.innerHTML = collapseCss;
    }
    screenlog?.classList.add("collapsed");
    localStorage.setItem("screenlog", "collapsed");
    handleExpandClick();
  }

  function handleShrinkClick() {
    if (localStorage.getItem("screenlog") === "expanded") {
      shrink();
      ex.addEventListener("click", handleExpandClick);
    }
  }

  function handleExpandClick() {
    if (localStorage.getItem("screenlog") !== "expanded") {
      expand();
      sh.addEventListener("click", handleShrinkClick);
    }
  }

  if (!localStorage.getItem("screenlog")) {
    localStorage.setItem("screenlog", "collapsed");
  }

  if (mainsection) {
    if (localStorage.getItem("screenlog")) {
      [shrink, expand][localStorage.getItem("screenlog") === "expanded" ? 1 : 0]();
    } else {
      shrink();
    }
  }

  document.addEventListener("DOMContentLoaded", () => {
    if (sh) {sh.addEventListener("click", handleShrinkClick);}
    if (ex) {ex.addEventListener("click", handleExpandClick);}
  });

}

export {initToggleLog};