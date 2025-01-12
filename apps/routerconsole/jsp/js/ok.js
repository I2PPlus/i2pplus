/* I2P+ ok.js by dr|z3d */
/* Provides a custom "OK" modal dialog */
/* Usage: modal("message" [,"button text"]) */
/* License: AGPL3 or later */

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