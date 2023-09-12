/* I2PSnark Inline Notifications */
/* Author: dr|z3d */
/* License: AGPL3 or later */

import {initSnarkRefresh, refreshTorrents, debouncedRefreshTorrents} from "./refreshTorrents.js";

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


/**
async function updateLog() {
  try {
    const xhrAlert = new XMLHttpRequest();
    xhrAlert.open("GET", url, true);
    xhrAlert.responseType = "document";
    xhrAlert.onload = function () {
      const response = xhrAlert.response;
      if (messages !== null && response !== null) {
        const screenLog = response.querySelector("#screenlog");
        if (screenLog !== null) {
          const newLogEntry = screenLog.querySelectorAll("li.msg")[0].innerText.substring(21);
          if (messages) {
            console.log(newLogEntry);
          }
          if (!addNotify.hidden) {
            addNotify.innerHTML = "<table><tr><td>" + newLogEntry + "</td></tr></table>";
          }
          if (!createNotify.hidden) {
            createNotify.innerHTML = "<table><tr><td>" + newLogEntry + "</td></tr></table>";
          }
        }
      }
    };
    xhrAlert.onerror = function(error) {
      console.error("Error updating log:", error);
    };
    xhrAlert.send();
  } catch (error) {
    console.error("Error updating log:", error);
  }
}
**/

/**
function updateLog() {
  const xhrAlert = new XMLHttpRequest();
  xhrAlert.open("GET", url, true);
  xhrAlert.responseType = "document";
  function reload() {
    xhrAlert.onload = function () {
      if (messages !== null) {
        if (xhrResponse.querySelectorAll("#screenlog li.msg")[0] !== null) {
          var newLogEntry = xhrResponse.querySelectorAll("#screenlog li.msg")[0].innerHTML.substring(21);
          if (messages) {
            console.log(newLogEntry);
          }
        }
        if (!addNotify.hidden)
          addNotify.innerHTML = "<table><tr><td>" + newLogEntry + "</td></tr></table>";
          if (!createNotify.hidden)
          createNotify.innerHTML = "<table><tr><td>" + newLogEntry + "</td></tr></table>";
      }
    }
  };
  xhrAlert.send();
}
**/

function updateLog() {
  return new Promise((resolve, reject) => {
    const xhrAlert = new XMLHttpRequest();
    const xhrResponse = xhrAlert.responseXML;
    xhrAlert.open("GET", url, true);
    xhrAlert.responseType = "document";
    xhrAlert.onload = function() {
      const logEntryEl = xhrResponse.querySelectorAll("#screenlog li.msg")[0];
      if (messages && logEntryEl) {
        const newLogEntry = logEntryEl.innerHTML.substring(21);
        console.log(newLogEntry);
        const newTable = "<table><tr><td>" + newLogEntry + "</td></tr></table>";
        if (!addNotify.hidden) addNotify.innerHTML = newTable;
        if (!createNotify.hidden) createNotify.innerHTML = newTable;
      }
      resolve();
    }
    xhrAlert.onerror = function(error) {
      console.error("Error updating log:", error);
      reject(error);
    };
    xhrAlert.send();
  });
}

function addTorrentNotify() {
  addNotify.removeAttribute("hidden");
  processForm.onload = function () {updateLog().catch(error => console.error("Error updating log:", error))};
  hideAlert();
  setTimeout(() => (inputAddFile.value = "", inputAddFile.focus()), 3000);
}

function createTorrentNotify() {
  createNotify.removeAttribute("hidden");
  processForm.onload = function () {updateLog().catch(error => console.error("Error updating log:", error))};
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