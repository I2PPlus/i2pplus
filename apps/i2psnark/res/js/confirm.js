/* I2P+ confirm.js by dr|z3d */
/* Custom confirm dialogs for I2PSnark */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", function () {

  const head = document.head;
  const htmlTag = document.documentElement;
  const modalCss = document.getElementById("modalCss");
  const fragment = document.createDocumentFragment();

  (function injectCss() {
    if (modalCss) {return;}
    const snarkTheme = head.querySelector("#snarkTheme");
    const css = document.createElement("style");
    css.id = "modalCss";
    css.textContent = ".modal{overflow:hidden;contain:paint}" +
                      "#confirmDialog:not(.cancelled):not(.postMsg){animation:slide-up .8s ease-out .2s both reverse}" +
                      "#confirmButtons{margin:0 -14px -20px;padding:15px;text-align:center}" +
                      "#confirmYes,#confirmNo{margin:4px 12px;padding:6px 8px;width:120px;font-weight:700;cursor:pointer}" +
                      "#confirmButtons button:hover{opacity:1}" +
                      "#confirmYes:active,#confirmNo:active{transform:scale(0.9)}" +
                      "#confirmDialog{padding:10px 15px 21px;width:480px;position:absolute;left:50%;z-index:100000;" +
                      "user-select:none;animation:fade .3s ease .8s both;transform:translate(-50%,-50%)}" +
                      "#confirmDialog.postMsg{animation:slide-down 5s ease-in 3s both reverse}" +
                      "#confirmDialog.cancelled{animation:slide-down 5s ease-in .2s both reverse}" +
                      "#confirmOverlay{width:100%;height:100%;position:fixed;left:0;bottom:0;right:0;z-index:99999}" +
                      "#confirmOverlay.cancelled{animation:fade .3s ease .2s both reverse}" +
                      "#confirmOverlay.done{animation:fade .3s ease 3s both reverse}" +
                      "#msg{margin:-9px -14px 0;padding:30px 20px 30px 88px;text-align:left;font-size:110%}" +
                      "#msg b{max-width:384px;display:inline-block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;vertical-align:bottom}" +
                      "#msg .hr{margin:11px 0 10px;height:0;width:100%;display:block}" +
                      "#msg.deleting{margin-bottom:-20px}" +
                      "@keyframes slide-down{0%{transform:translate(-50%, -3000px)}100%{transform:translate(-50%, -50%)}}" +
                      "@keyframes slide-up{0%{transform:translate(-50%, -50%)}100%{transform:translate(-50%, -500px)}}";
    fragment.appendChild(css);
    if (snarkTheme) {head.insertBefore(css, snarkTheme);}
    else {head.appendChild(css);}
    fragment.textContent = "";
  })();

  function handleResize() {
    const dialog = document.getElementById("confirmDialog");
    const dialogHeight = dialog.offsetHeight;
    const viewportHeight = htmlTag.classList.contains("iframed") ? parent.window.innerHeight : window.innerHeight;
    const topOffset = viewportHeight > 600 ? viewportHeight * 0.05 : 0;
    let topPosition = ((viewportHeight - dialogHeight) / 2) - topOffset;
    if (topPosition < 0) {topPosition = 0;}
    else if (topPosition + dialogHeight > viewportHeight) {topPosition = viewportHeight - dialogHeight;}
    dialog.style.top = topPosition + "px";
  }

  function customConfirm(options) {
    return new Promise((resolve) => {
      htmlTag.classList.add("modal");
      const dialog = document.createElement("div");
      const overlay = document.createElement("div");
      dialog.innerHTML = options.message +
                         "<p id=confirmButtons>" +
                         "<button id=confirmNo data-action=no>" + options.noLabel + "</button>" +
                         "<button id=confirmYes data-action=yes>" + options.yesLabel + "</button>" +
                         "</p>\n";
      dialog.id = "confirmDialog";
      overlay.id = "confirmOverlay";
      fragment.appendChild(overlay);
      fragment.appendChild(dialog);
      document.body.appendChild(fragment);
      fragment.textContent = "";

      requestAnimationFrame(() => {
        scrollToTop();
        handleResize();
      });

      window.addEventListener("resize", handleResize);

      overlay.addEventListener("click", (event) => { event.stopPropagation(); });

      dialog.addEventListener("click", (event) => {
        const target = event.target;
        const className = target.className;
        if (target.tagName === "BUTTON") {
          const action = target.dataset.action;
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

      htmlTag.addEventListener("keydown", captureKeyDown);

      function scrollToTop() {
        window.scrollTo(0,0);
        if (htmlTag.classList.contains("iframed")) {parent.window.scrollTo(0,0);}
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
        window.removeEventListener("resize", handleResize);
        document.getElementById("confirmDialog")?.remove();
        document.getElementById("confirmOverlay")?.remove();
        htmlTag.classList.remove("modal");
      }
    });
  }

  htmlTag.addEventListener("click", function (event) {
    const target = event.target;
    const form = document.getElementById("torrentlist");
    if (!target.matches("input") || !form) {return;}

    const className = target.className;
    if (className === "actionRemove" || className === "actionDelete") {
      event.preventDefault();
      let torrent = target.getAttribute("data-name");
      if (torrent.length > 50) {torrent = torrent.substring(0, 48) + "&hellip;";}
      let msg;
      msg = className === "actionRemove" ?
                          "<p id=msg>" + removeMsg + "<span class=hr></span>" + removeMsg2 + "</p>\n" :
                          "<p id=msg>" + deleteMsg + "<p>\n";
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