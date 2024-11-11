/* I2P+ confirm.js by dr|z3d */
/* Custom confirm dialogs for I2PSnark */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", function () {

  function customConfirm(options) {
    return new Promise((resolve) => {
      const fragment = document.createDocumentFragment();
      const overlay = document.createElement("div");
      overlay.id = "confirmOverlay";
      overlay.style.position = "fixed";
      overlay.style.top = "0";
      overlay.style.left = "0";
      overlay.style.width = "100%";
      overlay.style.height = "100%";
      overlay.style.zIndex = "99999";
      fragment.appendChild(overlay);

      const dialog = document.createElement("div");
      dialog.id = "confirmDialog";
      dialog.style.position = "absolute";
      dialog.style.top = "40%";
      dialog.style.left = "50%";
      dialog.style.transform = "translate(-50%, -50%)";
      dialog.style.zIndex = "100000";
      dialog.innerHTML = "<p id=msg>" + options.message + "</p>\n" +
                         "<p id=confirmButtons>" +
                         "<button id=confirmNo data-action=no>" + options.noLabel + "</button>" +
                         "<button id=confirmYes data-action=yes>" + options.yesLabel + "</button>" +
                         "</p>\n";
      fragment.appendChild(dialog);
      document.body.appendChild(fragment);
      document.documentElement.classList.add("modal");

      overlay.addEventListener("click", (event) => { event.stopPropagation(); });

      dialog.addEventListener("click", (event) => {
        const target = event.target;
        if (target.tagName === "BUTTON") {
          const action = target.dataset.action;
          if (action === "yes") {
            resolve(true);
            document.getElementById("confirmButtons").style.display = "none";
            document.getElementById("msg").classList.add("deleting");
            document.getElementById("msg").textContent = "Deleting...";
            setTimeout(() => { removeDialog(); }, 2 * 1000);
          } else if (action === "no") {
            resolve(false);
            removeDialog();
          }
        }
      });

      function removeDialog() {
        document.getElementById("confirmDialog").remove();
        document.getElementById("confirmOverlay").remove();
        document.documentElement.classList.remove("modal");
      }
    });
  }

  document.addEventListener("click", function (event) {
    const target = event.target;
    const form = document.getElementById("torrentlist");
    if (!target.matches("input") || !form) {return;}

    const className = target.className;
    if (className === "actionRemove" || className === "actionDelete") {
      event.preventDefault();
      const torrent = target.getAttribute("data-name");
      const msg = className === "actionRemove" ? deleteMessage1 : deleteMessage2;
      const name = target.name;
      const value = target.value;

      // Create confirmation dialog
      customConfirm({
        message: msg.replace("{0}", torrent),
        yesLabel: "Delete",
        noLabel: "Cancel"
      }).then((result) => {
        if (result) { // User confirmed deletion
          const hiddenInput = document.createElement("input");
          hiddenInput.type = "hidden";
          hiddenInput.name = name;
          hiddenInput.value = value;
          form.appendChild(hiddenInput);
          form.requestSubmit();
        }
      });
    }
  });

});