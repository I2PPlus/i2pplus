/**
 * @module snarkAlert
 * @file snarkAlert.js - Inline notification system for I2PSnark.
 * @description Handles form submission for adding and creating torrents, then displays
 * inline notification messages to the user. Integrates with the torrent refresh system
 * to update the screen log after operations complete.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { initSnarkRefresh, refreshTorrents, refreshScreenLog } from "./refreshTorrents.js";

"use strict";

/**
 * @type {Object}
 * @property {?HTMLElement} addNotify - The notification element for add-torrent operations.
 * @property {?HTMLFormElement} addTorrent - The add-torrent form element.
 * @property {?HTMLElement} createNotify - The notification element for create-torrent operations.
 * @property {?HTMLFormElement} createTorrent - The create-torrent form element.
 * @property {?HTMLInputElement} inputAddFile - The input field for the add-torrent URL.
 * @property {?HTMLInputElement} inputNewFile - The input field for the create-torrent file.
 * @property {?HTMLElement} processForm - The hidden iframe used for form submission.
 */
const elements = {
  addNotify: document.getElementById("addNotify"),
  addTorrent: document.getElementById("addForm"),
  createNotify: document.getElementById("createNotify"),
  createTorrent: document.getElementById("createForm"),
  inputAddFile: document.getElementById("addTorrentURL"),
  inputNewFile: document.getElementById("createTorrentFile"),
  processForm: document.getElementById("processForm")
};

/**
 * @type {?number}
 * @description Timeout ID for hiding the current alert notification.
 */
let hideAlertTimeoutId;

/**
 * @type {string}
 * @description The last notification message text, used for display purposes.
 */
let lastMessage = "";

/**
 * @async
 * @function handleTorrentNotify
 * @description Handles torrent form submission events. Prevents default form behavior,
 * submits the form via fetch, waits briefly for processing, refreshes the screen log,
 * and displays a notification with the result message.
 * @param {Event} event - The form submit event.
 * @param {HTMLElement} notificationElement - The DOM element to display the notification in.
 * @param {HTMLInputElement} inputElement - The form input field to clear after submission.
 * @param {HTMLFormElement} form - The form element to submit.
 * @returns {Promise<void>}
 */
async function handleTorrentNotify(event, notificationElement, inputElement, form) {
  event.preventDefault();
  if (elements.addNotify || elements.createNotify) {
    try {
      await submitForm(form);
      await new Promise(resolve => setTimeout(resolve, 1500));
      await refreshScreenLog(() => {
        requestAnimationFrame(() => { showNotification(notificationElement, inputElement, getLastMessage()); });
      }, true);
    } catch (error) {}
  }
}

/**
 * @function showNotification
 * @description Displays a notification message in the given element. Clears any existing
 * hide timeout, sets the notification content via innerHTML, and schedules automatic
 * dismissal after 6 seconds. Clears and refocuses the input element on hide.
 * @param {HTMLElement} notificationElement - The DOM element to show the notification in.
 * @param {HTMLInputElement} inputElement - The input field to clear and refocus after hiding.
 * @param {string} displayText - The HTML content to display in the notification.
 * @returns {void}
 */
function showNotification(notificationElement, inputElement, displayText) {
  if (hideAlertTimeoutId) {clearTimeout(hideAlertTimeoutId);}

  notificationElement.querySelector("td").innerHTML = displayText;
  notificationElement.removeAttribute("hidden");

  hideAlertTimeoutId = setTimeout(() => {
    hideAlert(notificationElement);
    inputElement.value = "";
    inputElement.focus();
  }, 6000);
}

/**
 * @function hideAlert
 * @description Hides a notification element by setting its hidden attribute. Uses
 * requestAnimationFrame to ensure smooth UI transitions.
 * @param {HTMLElement} notificationElement - The notification element to hide.
 * @returns {void}
 */
function hideAlert(notificationElement) {
  if (notificationElement) { requestAnimationFrame(() => {notificationElement.setAttribute("hidden", "");}) }
}

/**
 * @async
 * @function submitForm
 * @description Submits a form via fetch using FormData, preserving the form's method and action.
 * Throws an error if the response is not OK.
 * @param {HTMLFormElement} form - The form element to submit.
 * @returns {Promise<void>}
 * @throws {Error} If the form submission fails with a non-OK HTTP status.
 */
async function submitForm(form) {
  try {
    const formData = new FormData(form);
    const action = form.getAttribute("action");
    const response = await fetch(action, { method: form.method, body: formData });
    if (!response.ok) { throw new Error(`Form submission failed with status ${response.status}`); }
  } catch (error) {}
}

/**
 * @function getLastMessage
 * @description Extracts the latest message text from the screen log's first message element.
 * Strips leading non-breaking spaces and stores the result in the lastMessage variable.
 * @returns {string} The cleaned message text from the screen log.
 */
function getLastMessage() {
  const screenlog = document.getElementById("messages");
  const messageText = screenlog.querySelector("li.msg").innerHTML.trim();
  const index = messageText.indexOf("&nbsp; ");
  lastMessage = index !== -1 ? messageText.substring(index + 7) : messageText;
  return lastMessage;
}

/**
 * @function initSnarkAlert
 * @description Initializes the inline notification system on DOMContentLoaded. Registers
 * submit handlers on the add-torrent and create-torrent forms, wiring them to display
 * notifications upon completion.
 * @returns {void}
 */
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