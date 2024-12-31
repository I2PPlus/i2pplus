/* I2P+ click.js for I2PSnark by dr|z3d */
/* - Simulate longer button clicks by adding .depress class to inputs */
/* - Add custom confirm dialogs for remove and delete torrent events */
/* License: AGPL3 or later */

import {refreshScreenLog} from "./refreshTorrents.js";

let eventListenerActive = false;

document.addEventListener("DOMContentLoaded", () => {
  if (eventListenerActive) {return;}

  const page = document.getElementById("page");
  const htmlTag = document.documentElement;

  (function injectCss() {
    const head = document.head;
    const modalCss = document.getElementById("modalCss");
    const fragment = document.createDocumentFragment();
    const css = document.createElement("style");
    css.id = "modalCss";
    css.textContent =
      ".modal{overflow:hidden;contain:paint}" +
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
    const snarkTheme = head.querySelector("#snarkTheme");
    if (snarkTheme) head.insertBefore(css, snarkTheme);
    else head.appendChild(css);
    fragment.textContent = "";
  })();

  async function handleInputClick(clickTarget) {
    const clickable = ".toggleview, .tab_label, .snarkNav, .filter, input[class^='action'], input.add, input.create";
    if (!clickTarget.closest(clickable)) {
      console.log("element not in list of clickables, bailing out...");
      return;
    }
    const targetElement = clickTarget.matches(clickable) ? clickTarget : clickTarget.closest(clickable);
    console.log(targetElement);
    if (!targetElement) {return;}
    let delay = 360;
    const isAction = targetElement.matches("input[class^='action'], input[id^='action']");
    const isDeleteOrRemoved = targetElement.matches("input[class='actionDelete'], input[class='actionRemove']");
    const isFormButton = targetElement.matches("input[type=submit]");
    const currentForm = targetElement.closest("form");
    const isUIElement = targetElement.closest(".toggleview, .snarkNav, .filter");

    targetElement.classList.add("depress");

    if (isDeleteOrRemoved) {
      event.preventDefault();
      const confirmed = await showConfirmationDialog(targetElement, getConfirmationMessage(targetElement), targetElement.name, targetElement.value, targetElement.dataset.action);
      if (!confirmed) {return;}
    }

    if (isAction) {
      const iframe = document.getElementById("processForm");
      if (iframe) {
        const formTarget = targetElement.form.target;
        if (formTarget === "processForm" && isAction) {delay = 4000;}
      }
      const nonClickedActionButtons = currentForm.querySelectorAll("input[type=submit][class^='action']:not(.depress), input[type=submit][id^='action']:not(.depress)");
      nonClickedActionButtons.forEach((el) => el.classList.add("tempDisabled"));
      currentForm.onsubmit = async (event) => {
        await new Promise(resolve => setTimeout(resolve, 1000));
        await refreshScreenLog(undefined, true);
        await new Promise(resolve => setTimeout(resolve, 3000));
        targetElement.classList.replace("depress", "inert");
        nonClickedActionButtons.forEach((input) => input.classList.remove("tempDisabled"));
      };
    } else if (isFormButton) {
      setTimeout(async () => {
        await new Promise(resolve => setTimeout(resolve, 1000));
        await refreshScreenLog(undefined, true);
        clickTarget.classList.replace("depress", "inert");
      }, delay);
    } else {
      setTimeout(async() => { clickTarget.classList.replace("depress", "inert"); }, delay);
    }
    setTimeout(async () => {
      targetElement.classList.remove("depress");
      targetElement.classList.remove("inert");
    }, delay);
  };

  page.addEventListener("click", async (event) => {
    eventListenerActive = true;
    const clickTarget = event.target;
    const form = document.getElementById("torrentlist");
    const className = clickTarget.className;

    if (className === "actionRemove" || className === "actionDelete") {
      event.preventDefault();
      let torrent = clickTarget.getAttribute("data-name");
      if (torrent.length > 50) {torrent = torrent.substring(0, 48) + "&hellip;";}
      let msg;
      msg = className === "actionRemove" ? `<p id=msg>${removeMsg}<span class=hr></span>${removeMsg2}</p>` : `<p id=msg>${deleteMsg}</p>`;
      const name = clickTarget.name;
      const value = clickTarget.value;
      const action = clickTarget.dataset.action;

      const confirmed = await showConfirmationDialog(clickTarget, msg.replace("{0}", `<b>${torrent}</b>`), name, value, action);
      if (confirmed) {
        const hiddenInput = document.createElement("input");
        hiddenInput.type = "hidden";
        hiddenInput.name = name;
        hiddenInput.value = value;
        form.appendChild(hiddenInput);
        form.requestSubmit();
      }
    } else {
      if ((clickTarget.matches("input.add") || clickTarget.matches("input.create"))) {
        event.preventDefault();
        event.stopPropagation();
        clickTarget.form.requestSubmit();
      }
      handleInputClick(clickTarget);
    }
  });

  async function showConfirmationDialog(targetElement, message, inputName, inputValue, inputAction) {
      htmlTag.classList.add("modal");
      const dialog = document.createElement("div");
      const overlay = document.createElement("div");
      dialog.innerHTML = `${message}<p id=confirmButtons><button id=confirmNo data-action=no>Cancel</button><button id=confirmYes data-action=yes>Delete</button></p>`;
      dialog.id = "confirmDialog";
      overlay.id = "confirmOverlay";
      document.body.appendChild(overlay);
      document.body.appendChild(dialog);

      requestAnimationFrame(() => {
          scrollToTop();
          handleResize();
      });

      window.addEventListener("resize", handleResize);

      overlay.addEventListener("click", event => event.stopPropagation());

      const promise = new Promise((resolve) => {
          let confirmed = false;
          dialog.addEventListener("click", event => {
              const target = event.target;
              if (target.tagName === "BUTTON") {
                  const action = target.dataset.action;
                  if (action === "yes") {
                      dialog.classList.add("postMsg");
                      overlay.classList.add("done");
                      confirmed = true;
                  } else if (action === "no") {
                      dialog.classList.add("cancelled");
                      overlay.classList.add("cancelled");
                  }
                  removeDialog();
                  resolve(confirmed);
              }
          });
      });

      htmlTag.addEventListener("keydown", captureKeyDown);

      function scrollToTop() {
          window.scrollTo(0, 0);
          if (htmlTag.classList.contains("iframed")) parent.window.scrollTo(0, 0);
      }

      function captureKeyDown(event) {
          if (event.key === "Enter") {
              const confirmYesButton = document.querySelector("#confirmYes");
              if (confirmYesButton) confirmYesButton.click();
          } else if (event.key === "Escape") {
              const confirmNoButton = document.querySelector("#confirmNo");
              if (confirmNoButton) confirmNoButton.click();
          }
      }

      function removeDialog() {
          document.removeEventListener("keydown", captureKeyDown);
          window.removeEventListener("resize", handleResize);
          document.getElementById("confirmDialog")?.remove();
          document.getElementById("confirmOverlay")?.remove();
          htmlTag.classList.remove("modal");
      }

      function handleResize() {
          const dialog = document.getElementById("confirmDialog");
          const dialogHeight = dialog.offsetHeight;
          const viewportHeight = htmlTag.classList.contains("iframed") ? parent.window.innerHeight : window.innerHeight;
          const topOffset = viewportHeight > 600 ? viewportHeight * 0.05 : 0;
          let topPosition = ((viewportHeight - dialogHeight) / 2) - topOffset;
          if (topPosition < 0) topPosition = 0;
          else if (topPosition + dialogHeight > viewportHeight) topPosition = viewportHeight - dialogHeight;
          dialog.style.top = `${topPosition}px`;
      }

      return promise;
  }

  function getConfirmationMessage(targetElement) {
    const torrent = targetElement.getAttribute("data-name");
    if (torrent.length > 50) {
      torrent = torrent.substring(0, 48) + "&hellip;";
    }
    const className = targetElement.className;
    let msg;
    msg = className === "actionRemove" ? `<p id=msg>${removeMsg}<span class=hr></span>${removeMsg2}</p>` : `<p id=msg>${deleteMsg}</p>`;
    return msg.replace("{0}", `<b>${torrent}</b>`);
  }
});