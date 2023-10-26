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
let hideAlertTimeoutId;

function addTorrentNotify() {
  if (hideAlertTimeoutId) {clearTimeout(hideAlertTimeoutId);}
  if (notify) {
    setTimeout(function() {
      refreshScreenLog(showAddAlert);
    }, 1000);
  }
}

function createTorrentNotify() {
  if (hideAlertTimeoutId) {clearTimeout(hideAlertTimeoutId);}
  if (notify) {
    setTimeout(function() {
      refreshScreenLog(showCreateAlert);
    }, 1000);
  }
}

function showAddAlert() {
  addNotify.removeAttribute("hidden");
  setTimeout(() => {
    hideAlert();
    inputAddFile.value = "";
    inputAddFile.focus();
  }, 5000);
}

function showCreateAlert() {
  createNotify.removeAttribute("hidden");
  setTimeout(() => {
    hideAlert();
    inputNewFile.value = "";
    inputNewFile.focus();
  }, 5000);
}

function injectCss() {
  const alertCss = document.querySelector("#snarkAlert");
  if (!alertCss) {
    document.head.innerHTML += "<link id=snarkAlert rel=stylesheet href=/i2psnark/.res/snarkAlert.css>";
  }
}

function hideAlert() {
  hideAlertTimeoutId = setTimeout(() => {
    if (addNotify && createNotify) {
      addNotify.setAttribute("hidden", "");
      createNotify.setAttribute("hidden", "");
      notify.setAttribute("hidden", "");
    }
  }, 500);
}

function hideAlert() {
  timeoutId = setTimeout(() => {
    if (addNotify && createNotify) {
      addNotify.setAttribute("hidden", "");
      createNotify.setAttribute("hidden", "");
      notify.setAttribute("hidden", "");
    }
  }, 500);
}

function initSnarkAlert() {
  if (!addNotify) {return;}
  addTorrent.addEventListener("submit", addTorrentNotify);
  createTorrent.addEventListener("submit", createTorrentNotify);
  injectCss();
}

export {initSnarkAlert};
