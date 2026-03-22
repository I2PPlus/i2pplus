/**
 * @module configclients
 * @description Attaches confirmation dialogs to delete buttons on the /configclients
 * page to prevent accidental client removal.
 * @license AGPL3 or later
 */

(() => {
  document.addEventListener("DOMContentLoaded", () => {
    /**
     * Iterates over all delete control buttons and adds a confirmation prompt
     * that shows the client name before allowing deletion.
     */
    document.querySelectorAll(".control.delete").forEach(btn => {
      btn.addEventListener("click", event => {
        const client = btn.getAttribute("client");
        if (client && !confirm(deleteMessage.replace("{0}", client))) { event.preventDefault(); }
      });
    });
  });
})();