/* I2PSnark Inline Notifications */
/* Author: dr|z3d */
/* License: AGPL3 or later */

import {initSnarkRefresh, refreshTorrents, refreshScreenLog} from "./refreshTorrents.js";

"use strict";

const addNotify = document.getElementById("addNotify"), createNotify = document.getElementById("createNotify");
const addTorrent = document.getElementById("addForm"), createTorrent = document.getElementById("createForm");
const inputAddFile = document.querySelector("input[name='nofilter_newURL']"), inputNewFile = document.querySelector("input[name='nofilter_baseFile']");
const notify = document.getElementById("notify");
const processForm = document.getElementById("processForm");
const xhrsnarklog = new XMLHttpRequest();
let hideAlertTimeoutId;

function addTorrentNotify() {
  if (notify) {
    setTimeout(function() { refreshScreenLog(() => showNotification(addNotify, inputAddFile)); }, 1000);
  }
}

function createTorrentNotify() {
  if (notify) {
    setTimeout(function() { refreshScreenLog(() => showNotification(createNotify, inputNewFile)); }, 1000);
  }
}

function showNotification(notificationElement, inputElement) {
  if (hideAlertTimeoutId) { clearTimeout(hideAlertTimeoutId); }
  notificationElement.removeAttribute("hidden");
  setTimeout(() => {
    hideAlert();
    inputElement.value = "";
    inputElement.focus();
  }, 5000);
}

function hideAlert() {
  if (hideAlertTimeoutId !== null) {clearTimeout(hideAlertTimeoutId);}
  hideAlertTimeoutId = setTimeout(() => {
    if (addNotify && createNotify) {
      addNotify.setAttribute("hidden", "");
      createNotify.setAttribute("hidden", "");
      notify.setAttribute("hidden", "");
    }
  }, 10000);
}

function injectCss() {
  const alertCss = document.head.querySelector("#snarkAlert");
  if (!alertCss) {
    const link = document.createElement("link");
    link.id = "snarkAlert";
    link.rel = "stylesheet";
    link.href = "/i2psnark/.res/snarkAlert.css";
    if (document.head.firstChild) {document.head.insertBefore(link, document.head.firstChild);}
    else {document.head.appendChild(link);}
  }
}

function initSnarkAlert() {
  if (!addNotify) {return;}
  addTorrent?.removeEventListener("submit", addTorrentNotify);
  createTorrent?.removeEventListener("submit", createTorrentNotify);
  addTorrent?.addEventListener("submit", addTorrentNotify);
  createTorrent?.addEventListener("submit", createTorrentNotify);
  injectCss();
}

export {initSnarkAlert};
