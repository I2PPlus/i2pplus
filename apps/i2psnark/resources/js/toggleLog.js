function clean() {
  var expandLog = document.getElementById("expandLog");
  if (expandLog) {
      expandLog.remove();
  }
  var shrinkLog = document.getElementById("shrinkLog");
  if (shrinkLog) {
      shrinkLog.remove();
  }
}

function expand() {
  var x = document.createElement("link");
  x.type="text/css";
  x.rel="stylesheet";
  x.href=".resources/expand.css";
  x.setAttribute("id", "expandLog");
  document.head.appendChild(x);
}

function shrink() {
  var s = document.createElement("link");
  s.type="text/css";
  s.rel="stylesheet";
  s.href=".resources/shrink.css";
  s.setAttribute("id", "shrinkLog");
  document.head.appendChild(s);
}

