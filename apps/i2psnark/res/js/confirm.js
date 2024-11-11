/* I2P+ confirm.js by dr|z3d */
/* Custom confirm dialogs for I2PSnark */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", function () {

  const slider = document.createElement("style");
  slider.innerHTML = "#confirmDialog:not(.cancelled):not(.postMsg){animation:slide-up .8s ease-out 0s both reverse}" +
                     "@keyframes slide-up{0%{transform:translate(-50%, -50%)}100%{transform:translate(-50%, -500px)}}";
  document.body.appendChild(slider);

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
      dialog.style.zIndex = "100000";
      dialog.innerHTML = options.message +
                         "<p id=confirmButtons>" +
                         "<button id=confirmNo data-action=no>" + options.noLabel + "</button>" +
                         "<button id=confirmYes data-action=yes>" + options.yesLabel + "</button>" +
                         "</p>\n";
      fragment.appendChild(dialog);

      const styles = document.createElement("style");
      styles.id = "confirmStyles";
      styles.innerHTML = "#confirmDialog.postMsg{animation:slide-down 5s ease-in 3s both reverse}" +
                         "#confirmDialog.cancelled{animation:slide-down 5s ease-in 0s both reverse}" +
                         "#confirmOverlay.cancelled{animation:fade .3s ease 0s both reverse}" +
                         "#confirmOverlay.done{animation:fade .3s ease 3s both reverse}" +
                         "@keyframes slide-down{0%{transform:translate(-50%, -3000px)}100%{transform:translate(-50%, -50%)}}";
      fragment.appendChild(styles);
      document.body.appendChild(fragment);

      const htmlTag = document.documentElement;
      const dialogHeight = dialog.offsetHeight;
      const viewportHeight = htmlTag.classList.contains("iframed") ? parent.window.innerHeight : window.innerHeight;
      const topPosition = (viewportHeight - dialogHeight) / 2;
      dialog.style.top = topPosition + "px";
      dialog.id = "confirmDialog";
      dialog.style.position = "absolute";
      dialog.style.left = "50%";
      dialog.style.transform = "translate(-50%, -50%)";
      htmlTag.classList.add("modal");
      if (htmlTag.classList.contains("iframed") && htmlTag.classList.contains("modal")) {scrollToTop();}

      overlay.addEventListener("click", (event) => { event.stopPropagation(); });

      dialog.addEventListener("click", (event) => {
        const target = event.target;
        const className = target.className;
        if (target.tagName === "BUTTON") {
          const action = target.dataset.action;
          if (action !== null) {}
          if (action === "yes") {
            resolve(true);
            dialog.classList.add("postMsg");
            overlay.classList.add("done");
            let delMsg = options.className === "actionDelete" ? postDeleteMsg : postRemoveMsg + "...";
            delMsg = delMsg.replace("{0}", "<b>" + options.torrent + "</b>");
            document.getElementById("confirmButtons").style.display = "none";
            document.getElementById("msg").classList.add("deleting");
            document.getElementById("msg").innerHTML = delMsg;
            setTimeout(() => { removeDialog(); }, 4 * 1000);
          } else if (action === "no") {
            resolve(false);
            dialog.classList.add("cancelled");
            overlay.classList.add("cancelled");
            setTimeout(() => { removeDialog(); }, 1 * 1000);
          }
        }
      });

      document.addEventListener("keydown", captureKeyDown);

      function scrollToTop() {
        window.scrollTo(0,0);
        parent.window.scrollTo(0,0);
      }

      function captureKeyDown(event) {
        if (event.key === "Enter") {
          const confirmYesButton = document.querySelector("#confirmYes");
          if (confirmYesButton) {confirmYesButton.click();}
        } else if (event.key === "Escape") {
          const confirmNoButton = document.querySelector("#confirmNo");
          if (confirmNoButton) {confirmNoButton.click();}
        }
      }

      function removeDialog() {
        document.removeEventListener("keydown", captureKeyDown);
        document.getElementById("confirmDialog").remove();
        document.getElementById("confirmOverlay").remove();
        document.getElementById("confirmStyles").remove();
        htmlTag.classList.remove("modal");
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
      let msg;
      msg = className === "actionRemove" ?
                          "<p id=msg>" + removeMsg + "<span class=hr></span>" + removeMsg2 + "</p>\n" :
                          "<p>" + deleteMsg + "<p>\n";
      const name = target.name;
      const value = target.value;

      // Create confirmation dialog
      customConfirm({
        message: msg.replace("{0}", "<b>" + torrent + "</b>"),
        yesLabel: "Delete",
        noLabel: "Cancel",
        torrent: torrent,
        className: className,
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