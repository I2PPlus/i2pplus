/**
 * @module refreshInbox
 * @file I2P+ SusiMail inbox refresher.
 * Performs AJAX polling to refresh the mailbox view and notification
 * elements without a full page reload.
 * @author dr|z3d
 * @license AGPLv3 or later
 */

/** @type {HTMLFormElement|null} */
const form = document.querySelector('form[action="/susimail/"]');
/** @type {HTMLElement|null} */
const mailboxControls = document.getElementById("mailboxcontrols");
/** @type {HTMLElement|null} */
const mailbox = document.getElementById("mailbox");
/** @type {HTMLElement|null} */
const notify = document.getElementById("notify");
/** @type {HTMLElement|null} */
const pageRefresh = document.getElementById("pageRefresh");
/** @type {HTMLElement|null} */
const serverRefresh = document.getElementById("serverRefresh");

if (notify) { setTimeout(() => { notify.remove(); }, 4000); }

/**
 * Starts the inbox polling interval when the mailbox and refresh button
 * are present. Fetches the page, diffs relevant DOM sections, and
 * updates them in-place.
 */
if (mailbox && pageRefresh) {
  removeDupeNotices();
  const interval = setInterval(() => {
    if (document.getElementById("serverRefresh")) {
      clearInterval(interval);
      return;
    }
    if (pageRefresh) {pageRefresh.classList.add("checking");}
    fetch(`/susimail?${new Date().getTime()}`)
      .then(response => response.text())
      .then(html => {
        const parser = new DOMParser();
        const doc = parser.parseFromString(html, "text/html");
        const refresh = doc.getElementById("pageRefresh") || doc.getElementById("serverRefresh");
        const newMailbox = doc.getElementById("mailbox");
        const newNotify = doc.getElementById("notify");
        if (pageRefresh.outerHTML !== refresh.outerHTML) { pageRefresh.outerHTML = refresh.outerHTML;}
        if (mailbox.innerHTML !== newMailbox.innerHTML) {mailbox.innerHTML = newMailbox.innerHTML;}
        if (newNotify && (notify.innerHTML !== newNotify.innerHTML || !notify)) {
          if (notify) {notify.remove();}
          form.appendChild(newNotify);
        }
        removeDupeNotices();
      })
      .catch(() => {});
  }, 5000);
}

/**
 * Removes duplicate notification elements, keeping only the first one.
 * @function removeDupeNotices
 * @returns {void}
 */
function removeDupeNotices() {
  const notices = document.querySelectorAll(".notifications");
  if (!notices) {return;}
  for (let i = 1; i < notices.length; i++) {
      const toRemove = notices[i];
      toRemove.parentNode.removeChild(toRemove);
  }
}