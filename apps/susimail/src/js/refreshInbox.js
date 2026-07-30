/**
 * @module refreshInbox
 * @file I2P+ SusiMail inbox refresher.
 * Performs AJAX polling to refresh the mailbox view and notification
 * elements without a full page reload. The "Refresh Page" button also
 * triggers an immediate fetch on click.
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
 * Fetches the current mailbox HTML and diffs/updates the relevant DOM
 * sections (mailbox, notifications, refresh button) in-place.
 * @function doRefresh
 * @returns {void}
 */
function doRefresh() {
  if (document.getElementById("serverRefresh")) { return; }
  if (pageRefresh) { pageRefresh.classList.add("checking"); }
  fetch(`/susimail?${new Date().getTime()}`)
    .then(response => response.text())
    .then(html => {
      const parser = new DOMParser();
      const doc = parser.parseFromString(html, "text/html");
      const refresh = doc.getElementById("pageRefresh") || doc.getElementById("serverRefresh");
      const newMailbox = doc.getElementById("mailbox");
      const newNotify = doc.getElementById("notify");
      if (pageRefresh && refresh && pageRefresh.outerHTML !== refresh.outerHTML) {
        pageRefresh.outerHTML = refresh.outerHTML;
      }
      if (mailbox && newMailbox && mailbox.innerHTML !== newMailbox.innerHTML) {
        mailbox.innerHTML = newMailbox.innerHTML;
      }
      if (newNotify && (!notify || notify.innerHTML !== newNotify.innerHTML)) {
        if (notify) { notify.remove(); }
        if (form) { form.appendChild(newNotify); }
      }
      removeDupeNotices();
    })
    .catch(() => {});
}

/**
 * Starts the inbox polling interval when the mailbox and refresh button
 * are present. Also wires the "Refresh Page" button to trigger an
 * immediate fetch.
 */
if (mailbox && pageRefresh) {
  removeDupeNotices();
  if (pageRefresh) {
    pageRefresh.addEventListener("click", e => { e.preventDefault(); doRefresh(); });
  }
  const interval = setInterval(() => {
    if (document.getElementById("serverRefresh")) {
      clearInterval(interval);
      return;
    }
    doRefresh();
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