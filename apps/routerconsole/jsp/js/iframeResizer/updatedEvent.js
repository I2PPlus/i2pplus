/* I2P+ updatedEvent.js by dr|z3d */
/* Send event to parent window containing iframe when */
/* new page is loaded to force a scroll to top */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", function() {
  var newPage = new Event("updated");
  window.parent.document.dispatchEvent(newPage);
});