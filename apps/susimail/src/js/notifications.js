/**
 * @module notifications
 * @file SusiMail notification handler.
 * Auto-removes empty notification elements and adds a click-to-dismiss
 * handler for notification banners.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * IIFE that sets up DOM-based notification management.
 * Removes empty notices on load and allows manual dismissal via click.
 * @function removeNotify
 * @returns {void}
 */
(function removeNotify() {
  document.addEventListener("DOMContentLoaded", () => {
    const notice = document.getElementById("notify");
    if (notice) {
      if (notice.innerHTML === "") {notice.remove();}
      notice.addEventListener("click", () => {
        notice.remove();
        console.log("Notification nuked!");
      });
    }
  });
})();