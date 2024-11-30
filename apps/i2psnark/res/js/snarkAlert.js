/* I2PSnark Inline Notifications */
/* Author: dr|z3d */
/* License: AGPL3 or later */

import { initSnarkRefresh, refreshTorrents, refreshScreenLog } from "./refreshTorrents.js";

"use strict";

const elements = {
  addNotify: document.getElementById("addNotify"),
  createNotify: document.getElementById("createNotify"),
  addTorrent: document.getElementById("addForm"),
  createTorrent: document.getElementById("createForm"),
  inputAddFile: document.getElementById("addTorrentURL"),
  inputNewFile: document.getElementById("createTorrentFile"),
  processForm: document.getElementById("processForm")
};

let hideAlertTimeoutId;

async function handleTorrentNotify(event, notificationElement, inputElement, form) {
  event.preventDefault();
  if (elements.addNotify || elements.createNotify) {
    try {
      await submitForm(form);
      await refreshScreenLog(() => { showNotification(notificationElement, inputElement); }, true);
    } catch (error) {}
  }
}

function showNotification(notificationElement, inputElement) {
  if (hideAlertTimeoutId) {clearTimeout(hideAlertTimeoutId);}
  const screenlog = document.getElementById("messages");
  const lastMessage = screenlog.querySelector("li.msg");
  let displayText = "";
  if (lastMessage) {
    const messageText = lastMessage.innerHTML.trim();
    const index = messageText.indexOf("&nbsp; ");
    displayText = index !== -1 ? messageText.substring(index + 7) : messageText;
  }
  notificationElement.querySelector("td").textContent = displayText;
  notificationElement.removeAttribute("hidden");
  hideAlertTimeoutId = setTimeout(() => {
    hideAlert(notificationElement);
    inputElement.value = "";
    inputElement.focus();
  }, 5000);
}

function hideAlert(notificationElement) {
  if (notificationElement) {notificationElement.setAttribute("hidden", "");}
}

async function submitForm(form) {
  try {
    const formData = new FormData(form);
    const action = form.getAttribute("action");
    const response = await fetch(action, { method: form.method, body: formData });
    if (!response.ok) { throw new Error(`Form submission failed with status ${response.status}`); }
  } catch (error) {}
}

function initSnarkAlert() {
  document.addEventListener("DOMContentLoaded", () => {
    if (!elements.addNotify) {return;}
    [elements.addTorrent, elements.createTorrent].forEach(form => {
      if (form) {
        form.removeEventListener("submit", handleTorrentNotify);
        form.addEventListener("submit", (event) => {
          handleTorrentNotify(event,
            form === elements.addTorrent ? elements.addNotify : elements.createNotify,
            form === elements.addTorrent ? elements.inputAddFile : elements.inputNewFile, form
          );
        });
      }
    });
  });
}

initSnarkAlert();

export { initSnarkAlert };