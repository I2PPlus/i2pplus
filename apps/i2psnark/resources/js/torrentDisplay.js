// setup torrent display buttons so we can show/hide snarks based on status

function initFilterBar() {

  var btnAll = document.getElementById("all");
  var btnActive = document.getElementById("active");
  var btnInactive = document.getElementById("inactive");
  var btnDownloading = document.getElementById("downloading");
  var btnSeeding = document.getElementById("seeding");
  var btnComplete = document.getElementById("complete");
  var btnIncomplete = document.getElementById("incomplete");

  var filtered = false;

  function clean() {
    var filter = document.getElementById("filter");
    if (filter) {
        filter.remove();
    }
  }

  function showAll() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/all.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnAll.checked = true;
  }

  function showActive() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/active.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnActive.checked = true;
    filtered = true;
  }

  function showInactive() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/inactive.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnInactive.checked = true;
    filtered = true;
  }

  function showDownloading() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/downloading.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnDownloading.checked = true;
    filtered = true;
  }

  function showSeeding() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/seeding.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnSeeding.checked = true;
    filtered = true;
  }

  function showComplete() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/complete.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnComplete.checked = true;
    filtered = true;
  }

  function showIncomplete() {
    clean();
    var css = document.createElement("link");
    css.type="text/css";
    css.rel="stylesheet";
    css.href=".resources/filters/incomplete.css";
    css.setAttribute("id", "filter");
    document.head.appendChild(css);
    btnIncomplete.checked = true;
    filtered = true;
  }

  btnAll.addEventListener("click", showAll, false);
  btnActive.addEventListener("click", showActive, false);
  btnInactive.addEventListener("click", showInactive, false);
  btnDownloading.addEventListener("click", showDownloading, false);
  btnSeeding.addEventListener("click", showSeeding, false);
  btnComplete.addEventListener("click", showComplete, false);
  btnIncomplete.addEventListener("click", showIncomplete, false);

}

var main = document.getElementById("mainsection");
var bar = document.getElementById("torrentDisplay");
if (bar) {
  main.addEventListener("mouseover", function() {
    initFilterBar();
  }, false);
}
