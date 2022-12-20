var mainsection = document.getElementById("mainsection");
var screenlog = document.getElementById("screenlog");
var ex = document.getElementById("expand");
var sh = document.getElementById("shrink");

function initToggle() {

  function clean() {
    if (screenlog.classList.contains("xpanded"))
      screenlog.classList.remove("xpanded");
    if (screenlog.classList.contains("collapsed"))
      screenlog.classList.remove("collapsed");
    var expandLog = document.getElementById("expandLog");
    if (expandLog) {
        expandLog.remove();
    }
    var shrinkLog = document.getElementById("shrinkLog");
    if (shrinkLog) {
        shrinkLog.remove();
    }
    localStorage.setItem("screenlog", "collapsed");
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
      localStorage.setItem("screenlog", "collapsed");
    }

    function checkStatus() {
      var logStatus = localStorage.getItem("screenlog");
      switch (logStatus) {
        case "expanded":
          expand();
          break;
        case "collapsed":
          shrink();
          break;
        default:
          shrink();
      }
    }

    var logStatus = localStorage.getItem("screenlog");
    switch (logStatus) {
      case "expanded":
       expand();
        break;
      case "collapsed":
        shrink();
        break;
      default:
        shrink();
    }

  }

  sh.addEventListener("click", shrink, false);
  ex.addEventListener("click", expand, false);

}

sh.addEventListener("click", initToggle,false);
ex.addEventListener("click", initToggle,false);