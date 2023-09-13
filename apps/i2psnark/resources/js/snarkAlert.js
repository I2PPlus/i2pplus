/* I2PSnark Inline Notifications */
/* Author: dr|z3d */
/* License: AGPL3 or later */

import {initSnarkRefresh, refreshTorrents, debouncedRefreshTorrents, xhrsnark} from "./refreshTorrents.js";

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
let url = ".ajax/xhr1.html";

function updateLog() {
  const logEntryEl = xhrsnark.responseXML.querySelectorAll("#screenlog li.msg")[0];
  if (messages && logEntryEl) {
    const newLogEntry = logEntryEl.innerHTML.substring(21);
    //console.log(newLogEntry);
    const newTable = "<table><tr><td>" + newLogEntry + "</td></tr></table>";
    if (!addNotify.hidden) addNotify.innerHTML = newTable;
    if (!createNotify.hidden) createNotify.innerHTML = newTable;
  }
}

function addTorrentNotify() {
  addNotify.removeAttribute("hidden");
  processForm.onload = function () {refreshTorrents(updateLog);};
  hideAlert();
  setTimeout(() => (inputAddFile.value = "", inputAddFile.focus()), 3000);
}

function createTorrentNotify() {
  createNotify.removeAttribute("hidden");
  processForm.onload = function () {refreshTorrents(updateLog);};
  hideAlert();
  setTimeout(() => (inputNewFile.value = "", inputNewFile.focus()), 3000);
}

function injectCss() {
  if (!alertCss) {
    document.head.innerHTML += "<link id=snarkAlert rel=stylesheet href=/i2psnark/.resources/snarkAlert.css type=text/css>";
  }
}

function hideAlert() {
  setTimeout(() => (addNotify && createNotify && (addNotify.setAttribute("hidden", ""), createNotify.setAttribute("hidden", ""))), 7000);
}

addTorrent.addEventListener("submit", addTorrentNotify);
createTorrent.addEventListener("submit", createTorrentNotify);
injectCss();