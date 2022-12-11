/* Imagegen Loader by dr|z3d */
/* License: AGPLv3 or later */

var hostnameRegex = new RegExp("c=.*?&");
var qrTextRegex = new RegExp("t=.*?$");
var idButton = document.querySelector("#idgen button");
var identicon = document.getElementById("identicon");
var idForm = document.getElementById("idgen");
var qrForm = document.getElementById("qrgen");
var qrButton = document.querySelector("#qrgen button");

idButton.addEventListener("click", updateIdenticon);
qrButton.addEventListener("click", updateQrCode);

document.getElementById("randomartgen").addEventListener("submit",function() {
  var i2phost = document.getElementById("ra_hostname");
  var input = document.getElementById("ra_input").value;
  if (input !== null) {
    i2phost.innerText = input;
  }
});

function updateIdenticon(event) {
  event.preventDefault();
  var i;
  var i2phost = document.getElementById("id_hostname");
  var idImages = document.querySelectorAll('img[src*="id?"]');
  var input = document.getElementById("id_input").value;
  idForm.removeAttribute("target");
  idForm.removeAttribute("action");
  if (input !== null) {
    i2phost.innerText = input;
    for (i = 0; i < idImages.length; i++) {
      idImages[i].src = idImages[i].src.replace(hostnameRegex, "c=" + input + "&");
    }
  }
}

function hideIdDropdown() {
  var dropdown = document.getElementById("id_select");
  if (dropdown !== null) {dropdown.remove();}
}

function updateQrCode(event) {
  event.preventDefault();
  var inputText;
  var i2phost = document.getElementById("qr_hostname");
  var input = document.getElementById("qr_input").value;
  var qrText = document.getElementById("qr_inputText");
  if (qrText !== null && qrText.value !== null) {
    inputText = qrText.value;
  } else if (qrText !== null) {
    inputText = "";
  }
  var q;
  var qrImages = document.querySelectorAll('img[src*="qr?"]');
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
  var dropdown = document.getElementById("qr_select");
  if (dropdown !== null) {dropdown.remove();}
}

window.addEventListener("DOMContentLoaded", () => {
  hideIdDropdown();
  hideQrDropdown();
});