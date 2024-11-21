/* I2P+ refreshInbox.js for SusiMail by dr|z3d */
/* Perform an ajax fetch and handle page refresh when checkign mails */
/* License: AGPLv3 or later */

const form = document.querySelector('form[action="/susimail/"]');
const mailboxControls = document.getElementById("mailboxcontrols");
const mailbox = document.getElementById("mailbox");
const notify = document.getElementById("notify");
const pageRefresh = document.getElementById("pageRefresh");
const serverRefresh = document.getElementById("serverRefresh");

if (notify) { setTimeout(() => { notify.remove(); }, 4000); }

if (mailbox && pageRefresh) {
  const interval = setInterval(() => {
    if (document.getElementById("serverRefresh")) {
      clearInterval(interval);
      return;
    } else if (pageRefresh) {pageRefresh.classList.add("checking");}
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
      })
      .catch(() => {});
  }, 5000);
}