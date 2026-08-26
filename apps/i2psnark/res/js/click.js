/**
 * @module click
 * @file click.js - Simulate longer button clicks and custom confirm dialogs for I2PSnark.
 * @description Adds a .depress CSS class to input elements on click for visual feedback,
 * with extended timing for action buttons. Provides custom confirmation dialogs for
 * remove and delete torrent operations, with keyboard support (Enter/Escape), iframe-aware
 * positioning, and animated transitions.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import {refreshScreenLog} from "./refreshTorrents.js";

/**
 * @type {boolean}
 * @description Whether the main event listener has been registered.
 */
let eventListenerActive = false;

/**
 * @type {?HTMLElement}
 * @description The main configuration page element, used to skip initialization on config pages.
 */
const configPage = document.getElementById("mainconfig");

/**
 * @type {?HTMLElement}
 * @description The snark info element, used to skip initialization on info pages.
 */
const snarkInfo = document.getElementById("snarkInfo");

document.addEventListener("DOMContentLoaded", () => {
  if (eventListenerActive || configPage !== null || snarkInfo !== null) {return;}

  const page = document.getElementById("page") || document.querySelector(".page");
  const htmlTag = document.documentElement;

  /**
   * @function injectCss
   * @description Injects the confirmation dialog CSS styles into the document head.
   * Creates styles for the modal overlay, dialog box, buttons, and slide/fade animations.
   * Inserts the stylesheet before the #snarkTheme element if present.
   * @returns {void}
   */
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
      "#confirmDialog{padding:10px 15px 21px;width:480px;position:absolute;left:50%;z-index:100000;user-select:none;transform:translate(-50%,-50%)}" +
      "#confirmDialog.postMsg{animation:slide-down .2s ease-in .1s both reverse,fade .1s ease 0s both}" +
      "#confirmDialog.cancelled{animation:slide-down .2s ease-in .1s both reverse,fade .1s ease 0s both}" +
      "#confirmOverlay{width:100%;height:100%;position:fixed;left:0;bottom:0;right:0;z-index:99999}" +
      "#confirmOverlay.cancelled{animation:fade .3s ease .2s both reverse}" +
      "#confirmOverlay.done{animation:fade .3s ease 0s both reverse}" +
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

  /**
   * @async
   * @function handleInputClick
   * @description Handles clicks on interactive UI elements (toggle views, tabs, navigation,
   * filters, action buttons, form buttons). Adds a .depress class for visual feedback,
   * manages action button state (disabling other buttons during processing), and submits
   * action forms with the clicked button as submitter.
   * @param {HTMLElement} clickTarget - The element that was clicked.
   * @param {Event} event - The originating click event.
   * @returns {Promise<void>}
   */
  async function handleInputClick(clickTarget, event) {
    const clickable = ".toggleview, .tab_label, .snarkNav, .filter, input[class^='do'], input[id^='do'], input.add, input.create";
    if (!clickTarget.closest(clickable)) {return;}

    const targetElement = clickTarget.matches(clickable) ? clickTarget : clickTarget.closest(clickable);
    if (!targetElement) {return;}

    const isAction = targetElement.matches("input[class^='do'], input[id^='do']");
    const isFormButton = targetElement.matches("input[type=submit]");

    let currentForm;
    let delay = 360;

    targetElement.classList.add("depress");

    if (isAction) {
      const iframe = document.getElementById("processForm");
      currentForm = document.getElementById("torrentlist");
      if (!iframe || !currentForm) {return;}
      const formTarget = targetElement.form.target;
      if (formTarget === "processForm") {delay = 4000;}
      const nonClickedActionButtons = currentForm.querySelectorAll("input[type=submit][class^='do']:not(.depress), input[type=submit][id^='do']:not(.depress)");
      nonClickedActionButtons.forEach((el) => el.classList.add("tempDisabled"));
      setTimeout(() => {
        nonClickedActionButtons.forEach((input) => input.classList.remove("tempDisabled"));
      }, 4000);
      event.preventDefault();
      currentForm.requestSubmit(targetElement);
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

    if (clickTarget.classList.contains("doDelete") || clickTarget.classList.contains("doRemove")) {
      event.preventDefault();
      let torrent = clickTarget.getAttribute("data-name");
      if (torrent.length > 50) {torrent = torrent.substring(0, 48) + "&hellip;";}
      const msg = clickTarget.classList.contains("doRemove") ? `<p id=msg>${removeMsg}<span class=hr></span>${removeMsg2}</p>` : `<p id=msg>${deleteMsg}</p>`;
      const name = clickTarget.name;
      const value = clickTarget.value;
      const action = clickTarget.dataset.action;

      const confirmed = await showConfirmationDialog(clickTarget, msg.replace("{0}", `<b>${torrent}</b>`), name, value, action);
      if (form && confirmed) {
        form.requestSubmit(clickTarget);
      }
    } else {
      if ((clickTarget.matches("input.add") || clickTarget.matches("input.create"))) {
        event.preventDefault();
        event.stopPropagation();
        clickTarget.form.requestSubmit(clickTarget);
      }
      handleInputClick(clickTarget, event);
    }
    if (clickTarget.classList.contains("action") || clickTarget.id.includes("action")) {
      clickTarget.disabled = true;
      clickTarget.classList.add("depress");
    }
  });

  /**
   * @async
   * @function showConfirmationDialog
   * @description Creates and displays a modal confirmation dialog with the given message.
   * Supports "yes" (confirm) and "no" (cancel) actions. Handles keyboard events
   * (Enter to confirm, Escape to cancel), window resize repositioning, and animated
   * entrance/exit transitions. Returns a Promise that resolves to true if confirmed.
   * @param {HTMLElement} targetElement - The element that triggered the dialog.
   * @param {string} message - The HTML message to display in the dialog body.
   * @param {string} inputName - The form input name attribute for the confirmed action.
   * @param {string} inputValue - The form input value for the confirmed action.
   * @param {string} inputAction - The data-action attribute value for the confirmed action.
   * @returns {Promise<boolean>} Resolves to true if the user confirms, false if cancelled.
   */
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

      window.addEventListener("resize", handleResize, {passive: true});

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

      /**
       * @function scrollToTop
       * @description Scrolls both the window and parent window (if iframed) to the top
       * to ensure the confirmation dialog is visible.
       * @returns {void}
       */
      function scrollToTop() {
          window.scrollTo(0, 0);
          if (htmlTag.classList.contains("iframed")) { parent.window.scrollTo(0, 0); }
      }

      /**
       * @function captureKeyDown
       * @description Handles keyboard events for the confirmation dialog. Enter triggers
       * the confirm (yes) button, Escape triggers the cancel (no) button.
       * @param {KeyboardEvent} event - The keyboard event.
       * @returns {void}
       */
      function captureKeyDown(event) {
          if (event.key === "Enter") {
              const confirmYesButton = document.querySelector("#confirmYes");
              if (confirmYesButton) { confirmYesButton.click(); }
          } else if (event.key === "Escape") {
              const confirmNoButton = document.querySelector("#confirmNo");
              if (confirmNoButton) { confirmNoButton.click(); }
          }
      }

      /**
       * @function removeDialog
       * @description Removes the confirmation dialog and overlay from the DOM, cleans up
       * keyboard and resize listeners, and removes the modal class.
       * @returns {void}
       */
      function removeDialog() {
          document.removeEventListener("keydown", captureKeyDown);
          window.removeEventListener("resize", handleResize);
          document.getElementById("confirmDialog")?.remove();
          document.getElementById("confirmOverlay")?.remove();
          htmlTag.classList.remove("modal");
      }

      /**
       * @function handleResize
       * @description Repositions the confirmation dialog vertically centered in the viewport,
       * accounting for iframe mode and a small offset for better visual placement on taller
       * viewports.
       * @returns {void}
       */
      function handleResize() {
          const dialog = document.getElementById("confirmDialog");
          if (!dialog) {return;}
          const dialogHeight = dialog.offsetHeight;
          const viewportHeight = htmlTag.classList.contains("iframed") ? parent.window.innerHeight : window.innerHeight;
          const topOffset = viewportHeight > 600 ? viewportHeight * 0.05 : 0;
          let topPosition = ((viewportHeight - dialogHeight) / 2) - topOffset;
          if (topPosition < 0) { topPosition = 0; }
          else if (topPosition + dialogHeight > viewportHeight) { topPosition = viewportHeight - dialogHeight; }
          dialog.style.top = `${topPosition}px`;
      }

      return promise;
  }
});
