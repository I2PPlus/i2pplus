const mainsection = document.getElementById("mainsection");
const screenlog = document.getElementById("screenlog");

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

  }

  const ex = document.getElementById("expand");
  const sh = document.getElementById("shrink");

  if (sh)
    sh.addEventListener("click", shrink, false);
  if (ex)
    ex.addEventListener("click", expand, false);

}

screenlog.addEventListener("mouseover", function() {
  initToggle();
}, false);
