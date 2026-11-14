/**
 * @module imagegen
 * @description Drives the identicon, QR-code and random-art forms on the
 * imagegen webapp page: each form repoints its preview image at the imagegen
 * servlet with the current hostname or text, so the picture follows the input
 * without a submit.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function imagegen() {

  const hostnameRegex = new RegExp("c=.*?&");
  const qrTextRegex = new RegExp("t=.*?$");
  const idButton = document.querySelector("#idgen button");
  const identicon = document.getElementById("identicon");
  const idForm = document.getElementById("idgen");
  const qrForm = document.getElementById("qrgen");
  const qrButton = document.querySelector("#qrgen button");

  idButton.addEventListener("click", updateIdenticon);
  qrButton.addEventListener("click", updateQrCode);

  document.getElementById("randomartgen").addEventListener("submit",function() {
    const i2phost = document.getElementById("ra_hostname");
    const input = document.getElementById("ra_input").value;
    if (input !== null) {
      i2phost.innerText = input;
    }
  });

  function updateIdenticon(event) {
    event.preventDefault();
    const i2phost = document.getElementById("id_hostname");
    const idImages = document.querySelectorAll('img[src*="id?"]');
    const input = document.getElementById("id_input").value;
    idForm.removeAttribute("target");
    idForm.removeAttribute("action");
    if (input !== null) {
      i2phost.innerText = input;
      for (let i = 0; i < idImages.length; i++) {
        idImages[i].src = idImages[i].src.replace(hostnameRegex, "c=" + input + "&");
      }
    }
  }

  function hideIdDropdown() {
    const dropdown = document.getElementById("id_select");
    if (dropdown !== null) {dropdown.remove();}
  }

  function updateQrCode(event) {
    event.preventDefault();
    let inputText;
    const i2phost = document.getElementById("qr_hostname");
    const input = document.getElementById("qr_input").value;
    const qrText = document.getElementById("qr_inputText");
    if (qrText !== null && qrText.value !== null) {
      inputText = qrText.value;
    } else if (qrText !== null) {
      inputText = "";
    }
    let q;
    const qrImages = document.querySelectorAll('img[src*="qr?"]');
    qrForm.removeAttribute("target");
    qrForm.removeAttribute("action");
    if (input !== null) {
      i2phost.innerText = input;
      if (inputText !== null) {
        qrText.innerText = inputText;
      }
      for (q = 0; q < qrImages.length; q++) {
        qrImages[q].src = qrImages[q].src.replace(hostnameRegex, "c=" + input + "&");
        if (inputText !== null) {
          qrImages[q].src = qrImages[q].src.replace(qrTextRegex, "t=" + inputText);
        } else {
          qrImages[q].src = qrImages[q].src.replace(qrTextRegex, "");
        }
      }
    }
  }

  function hideQrDropdown() {
    const dropdown = document.getElementById("qr_select");
    if (dropdown !== null) {dropdown.remove();}
  }

  window.addEventListener("DOMContentLoaded", () => {
    hideIdDropdown();
    hideQrDropdown();
  });

  // these stay on the window: other scripts and the page's inline
  // handlers reach them there
  window.identicon = identicon;
}());
