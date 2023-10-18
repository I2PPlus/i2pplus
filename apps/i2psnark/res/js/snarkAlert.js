/* I2PSnark Inline Notifications */
/* Author: dr|z3d */
/* License: AGPL3 or later */

import {initSnarkRefresh, refreshTorrents, refreshScreenLog} from "./refreshTorrents.js";

"use strict";

const addNotify = document.getElementById("addNotify");
const addTorrent = document.getElementById("addForm");
const createNotify = document.getElementById("createNotify");
const createTorrent = document.getElementById("createForm");
const inputAddFile = document.querySelector("input[name='nofilter_newURL']");
const inputNewFile = document.querySelector("input[name='nofilter_baseFile']");
const notify = document.getElementById("notify");
const processForm = document.getElementById("processForm");
const xhrsnarklog = new XMLHttpRequest();

function addTorrentNotify() {
  if (notify) {
    setTimeout(function() {
      refreshScreenLog(showAlert);
    }, 1000);
  }
}

function showAlert() {
  addNotify.removeAttribute("hidden");
  if (!addNotify.hidden) {
    setTimeout(() => {
      hideAlert();
      inputAddFile.value = "";
      inputAddFile.focus();
    }, 5000);
  }
}

function createTorrentNotify() {
  createNotify.removeAttribute("hidden");
  processForm.onload = refreshScreenLog();
  hideAlert();
  setTimeout(() => (inputNewFile.value = "", inputNewFile.focus()), 3000);
}

function injectCss() {
  const alertCss = document.querySelector("#snarkAlert");
  if (!alertCss) {
    document.head.innerHTML += "<link id=snarkAlert rel=stylesheet href=/i2psnark/.res/snarkAlert.css>";
  }
}

function hideAlert() {
  setTimeout(() => {
    if (addNotify && createNotify) {
      addNotify.setAttribute("hidden", "");
      //notifyTable.setAttribute("hidden", "");
      createNotify.setAttribute("hidden", "");
      notify.setAttribute("hidden", "");
    }
  }, 7000);
}

function initSnarkAlert() {
  if (!addNotify) {return;}
  addTorrent.addEventListener("submit", addTorrentNotify);
  createTorrent.addEventListener("submit", createTorrentNotify);
  injectCss();
}

export {initSnarkAlert};
