/* I2PSnark Inline Notifications */
/* Author: dr|z3d */
/* License: AGPL3 or later */

"use strict";

const addNotify = document.getElementById("addNotify");
const addTorrent = document.getElementById("addForm");
const alertCss = document.querySelector("#snarkAlert");
const createNotify = document.getElementById("createNotify");
const createTorrent = document.getElementById("createForm");
const inputAddFile = document.querySelector("input[name='nofilter_newURL']");
const inputNewFile = document.querySelector("input[name='nofilter_baseFile']");
const messages = document.getElementById("screenlog");
const processForm = document.querySelector("iframe");
var url = ".ajax/xhr1.html";
const xhrLog = new XMLHttpRequest();

function updateUrl() {
  var filterbar = document.getElementById("torrentDisplay");
  var headers = new Headers();
  var pagesize = headers.get("X-Snark-Pagesize");
  var query = window.location.search;
  var storage = localStorage.getItem("filter");

  if (query) {
    if (storage && filterbar) {
      url += query + "&ps=9999";
    } else {
      url += query;
    }
  } else if (storage && filterbar) {
    url += "?ps=9999";
  }
}

function updateLog() {
  updateUrl();
  xhrLog.open("GET", url, true);
  xhrLog.responseType = "document";
  function reload() {
    xhrLog.onreadystatechange = function () {
      if (xhrLog.readyState == 4 && xhrLog.status == 200) {
        var newLogEntry = xhrLog.responseXML.querySelectorAll("#screenlog li.msg")[0].innerHTML.substring(21);
        if (messages) {
          console.log(newLogEntry);
        }
        if (!addNotify.hidden)
        addNotify.innerHTML = "<table><tr><td>" + newLogEntry + "</td></tr></table>";
        if (!createNotify.hidden)
        createNotify.innerHTML = "<table><tr><td>" + newLogEntry + "</td></tr></table>";
      }
    };
    xhrLog.send();
  }
  reload();
}

function addTorrentNotify() {
  addNotify.removeAttribute("hidden");
  processForm.onload = function () {updateLog()};
  hideAlert();
  setTimeout(function () {inputAddFile.value=""; inputAddFile.focus();}, 3000);
}

function createTorrentNotify() {
  createNotify.removeAttribute("hidden");
  processForm.onload = function () {updateLog()};
  hideAlert();
  setTimeout(function () {inputNewFile.value=""; inputNewFile.focus();}, 3000);
}

function injectCss() {
  if (!alertCss) {
    document.head.innerHTML += "<link id=snarkAlert rel=stylesheet href=/i2psnark/.resources/snarkAlert.css type=text/css>";
    //document.head.innerHTML += "<link id=snarkAlert rel=stylesheet href=/themes/snark/snarkAlert.css type=text/css>"; // debugging
  }
}

function hideAlert() {
  setTimeout(function () {
    if (addNotify) {
      addNotify.setAttribute("hidden", "");
    }
    if (createNotify) {
      createNotify.setAttribute("hidden", "");
    }
  }, 7000);
}

addTorrent.addEventListener("submit", addTorrentNotify);
createTorrent.addEventListener("submit", createTorrentNotify);
injectCss();
updateLog();
