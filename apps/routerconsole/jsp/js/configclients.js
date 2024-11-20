(() => {
  document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll(".control.delete").forEach(btn => {
      btn.addEventListener("click", event => {
        const client = btn.getAttribute("client");
        if (client && !confirm(deleteMessage.replace("{0}", client))) event.preventDefault();
      });
    });
  });
})();