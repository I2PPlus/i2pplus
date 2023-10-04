/* I2PSnark Inline Notifications */
/* Author: dr|z3d */
/* License: AGPL3 or later */

import {initSnarkRefresh, refreshTorrents, xhrsnarklog} from "./refreshTorrents.js";

"use strict";

const addNotify = document.getElementById("addNotify");
const addTorrent = document.getElementById("addForm");
const alertCss = document.querySelector("#snarkAlert");
const createNotify = document.getElementById("createNotify");
const createTorrent = document.getElementById("createForm");
const inputAddFile = document.querySelector("input[name='nofilter_newURL']");
const inputNewFile = document.querySelector("input[name='nofilter_baseFile']");
const messages = document.getElementById("screenlog");
const processForm = document.getElementById("processForm");
let url = ".ajax/xhr1.html";

function updateLog() {
  const messages = xhrsnarklog.responseXML.querySelector("messages");
  if (messages) {
    const logEntryEl = xhrsnarklog.responseXML.querySelector("#screenlog li.msg");
    if (logEntryEl) {
      console.log(logEntryEl.innerHTML);
      const newLogEntry = logEntryEl.innerHTML.substring(21);
      //console.log("Alert notification should read: " + newLogEntry);
      const newTable = "<table><tr><td>" + newLogEntry + "</td></tr></table>";
      if (!addNotify.hidden) addNotify.innerHTML = newTable;
      if (!createNotify.hidden) createNotify.innerHTML = newTable;
    }
  }
}

function addTorrentNotify() {
  addNotify.removeAttribute("hidden");
  processForm.onload = function() {
    if (xhrsnarklog.responseXML) {
      const messages = xhrsnarklog.responseXML.getElementById("screenlog");
      if (messages) {
        const logEntryEl = xhrsnarklog.responseXML.querySelector("#screenlog li.msg");
        if (logEntryEl) {
          const newLogEntry = logEntryEl.innerHTML.substring(21);
          const newTable = "<table><tr><td>" + newLogEntry + "</td></tr></table>";
          if (!addNotify.hidden) addNotify.innerHTML = newTable;
          if (!createNotify.hidden) createNotify.innerHTML = newTable;
        }
      }
    }
  };
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
    document.head.innerHTML += "<link id=snarkAlert rel=stylesheet href=/i2psnark/.res/snarkAlert.css type=text/css>";
  }
}

function hideAlert() {
  setTimeout(() => (addNotify && createNotify && (addNotify.setAttribute("hidden", ""), createNotify.setAttribute("hidden", ""))), 7000);
}

function initSnarkAlert() {
  if (!addNotify) {return;}
  addTorrent.addEventListener("submit", addTorrentNotify);
  createTorrent.addEventListener("submit", createTorrentNotify);
  injectCss();
}

export {initSnarkAlert};
