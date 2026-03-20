/**
 * @module ok
 * @description Provides a custom modal dialog with a message and OK button.
 * Creates a centered overlay with auto-repositioning on window resize.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Displays a modal dialog with the given message and an OK button.
 * Returns a Promise that resolves when the user clicks OK.
 * @function modal
 * @param {string} msg - The HTML message to display in the modal
 * @param {string} [buttonText="OK"] - The text for the OK button
 * @returns {Promise<void>} Resolves when the modal is dismissed
 * @example modal("Configuration saved successfully!")
 * @example modal("An error occurred.", "Dismiss")
 */
const modal = (msg, buttonText = "OK") => {
  return new Promise((resolve) => {
    const fragment = document.createDocumentFragment();
    const modal = document.createElement("div");
    const modalText = document.createElement("p");
    const ok = document.createElement("button");
    const overlay = document.createElement("div");

    modal.id = "modal";
    modalText.innerHTML = msg;
    modal.appendChild(modalText);
    ok.textContent = buttonText;
    ok.id = "ok";
    modal.appendChild(ok);
    overlay.id = "overlay";
    fragment.appendChild(overlay);
    fragment.appendChild(modal);
    document.body.appendChild(fragment);

    const modalDialog = document.getElementById("modal");
    const overlayLayer = document.getElementById("overlay");
    const okButton = modalDialog.querySelector("#ok");

    /**
     * Centers the modal dialog in the viewport and focuses the OK button.
     * @function centerModal
     * @returns {void}
     */
    const centerModal = () => {
      const rect = modalDialog.getBoundingClientRect();
      const windowHeight = window.innerHeight;
      const windowWidth = window.innerWidth;
      const topPosition = Math.max(10, (windowHeight - rect.height) / 2 - windowHeight * 0.05);
      modalDialog.style.top = `${topPosition}px`;
      modalDialog.style.left = `${(windowWidth - rect.width) / 2}px`;
      okButton.focus();
      document.body.classList.add("modalActive");
    };

    centerModal();
    window.addEventListener("resize", centerModal, {passive: true});

    /**
     * Handles the Enter key press on the OK button, triggering the click action.
     * @function handleEnterKey
     * @param {KeyboardEvent} event - The keyboard event
     * @returns {void}
     */
    const handleEnterKey = (event) => {
      if (event.target === okButton && event.key === "Enter") {
        event.preventDefault();
        event.stopImmediatePropagation();
        okButton.click();
      }
    };

    okButton.onclick = () => {
      modalDialog.remove();
      overlayLayer.remove();
      window.removeEventListener("resize", centerModal);
      document.removeEventListener("keydown", handleEnterKey);
      setTimeout(() => { document.body.classList.remove("modalActive"); }, 300);
      resolve();
    };

    document.addEventListener("keydown", handleEnterKey);
  });
};