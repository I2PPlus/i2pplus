/* toggleLog.js by dr|3d */
/* Enable toggling height of I2PSnark's screenlog */
/* License: AGPL3 or later */

var mainsection = document.getElementById("mainsection");
var screenlog = document.getElementById("screenlog");
var ex = document.getElementById("expand");
var sh = document.getElementById("shrink");

function initToggle() {
  function clean() {
    var expandLog = document.getElementById("expandLog");
    var shrinkLog = document.getElementById("shrinkLog");
    if (screenlog.classList.contains("xpanded")) {screenlog.classList.remove("xpanded");}
    if (screenlog.classList.contains("collapsed")) {screenlog.classList.remove("collapsed");}
    if (expandLog) {expandLog.remove();}
    if (shrinkLog) {shrinkLog.remove();}
    localStorage.removeItem("screenlog");
  }
  if (mainsection) {
    function expand() {
      clean();
      var x = document.createElement("link");
      x.type="text/css";
      x.rel="stylesheet";
      x.href=".resources/expand.css";
      x.setAttribute("id", "expandLog");
      document.head.appendChild(x);
      screenlog.classList.add("xpanded");
      localStorage.setItem("screenlog", "expanded")
    }
    function shrink() {
      clean();
      var s = document.createElement("link");
      s.type="text/css";
      s.rel="stylesheet";
      s.href=".resources/shrink.css";
      s.setAttribute("id", "shrinkLog");
      document.head.appendChild(s);
      screenlog.classList.add("collapsed");
    }
    function checkStatus() {
      var logStatus = localStorage.getItem("screenlog");
      switch (logStatus) {
        case "expanded":
          expand();
          break;
        default:
          shrink();
      }
    }
  }
  checkStatus();
  sh.addEventListener("click", shrink, false);
  ex.addEventListener("click", expand, false);
}
document.addEventListener("DOMContentLoaded", () => {
  sh.addEventListener("click", initToggle, false);
  ex.addEventListener("click", initToggle, false);
});