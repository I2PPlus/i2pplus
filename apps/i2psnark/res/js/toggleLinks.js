/* I2PSnark toggleLinks.js by dr|z3d */
/* Provide a toggle switch for magnets or links in the main torrent table */
/* License: AGPL3 or later */

const linkCss =    "#torrents #linkswitch::before{background:url(/i2psnark/.res/icons/link.svg) no-repeat center center/20px!important}" +
                   "#snarkTbody .magnet,#snarkTbody .magnet:empty{padding:0;width:0;font-size:0}#snarkTbody .magnet>*{display:none}" +
                   "#snarkTbody .trackerLink{padding:4px;width:1%;vertical-align:middle;text-align:center;font-size:0}#snarkTbody .trackerLink>*{display:inline-block}" +
                   "#torrents td.trackerLink img{margin:0;width:18px!important;height:18px!important}" +
                   "@media(min-width:1500px){#torrents td.trackerLink img{margin:0;width:20px!important;height:20px!important}";

const magnetCss =  "#torrents #linkswitch::before{background:url(/i2psnark/.res/icons/magnet.svg) no-repeat center center/20px!important}" +
                   "#snarkTbody .trackerLink,#snarkTbody .trackerLink:empty{padding:0;width:0;font-size:0}#snarkTbody .trackerLink>*{display:none}" +
                   "#snarkTbody .magnet{padding:4px;width:1%;vertical-align:middle;text-align:center;font-size:0}#snarkTbody .magnet>*{display:inline-block}" +
                   "#torrents td.trackerLink img{margin:0;width:18px!important;height:18px!important}" +
                   "@media(min-width:1500px){#torrents td.magnet img{margin:0;width:20px!important;height:20px!important}}";

 const magnetBtn = "#snarkTbody .magnetlink{position:relative}#snarkTbody .copyMagnet{width:100%;height:100%;position:absolute;top:0;right:0;bottom:0;left:0;" +
                   "border:0}";

var config = localStorage.getItem("linkToggle");
const magnets = document.querySelectorAll("#snarkTbody td.magnet .magnetlink");
const mlinks = document.querySelectorAll("#snarkTbody .magnet");
const tlinks = document.querySelectorAll("#snarkTbody .trackerLink");
const toast = document.querySelector("#toast");
const toggleCss = document.getElementById("toggleLinks");
const toggle = document.getElementById("linkswitch");

function initLinkToggler() {

  if (!toggle) {return;}
  toggle.removeAttribute("hidden");
  toggle.removeAttribute("checked");


  if (config === "links") {
    showLinks();
    removeMagnetListeners();
    toggle.click();
    toggle.checked = false;
    toggleCss.textContent = linkCss + magnetBtn;
  } else if (config === "magnets" || !config) {
    toggle.click();
    toggle.checked = true;
    showMagnets();
    magnetToClipboard();
    attachMagnetListeners();
    toggleCss.textContent = magnetCss + magnetBtn;
  }
  toggle.removeEventListener("click", linkToggle, true);
  toggle.addEventListener("click", linkToggle, true);
}

function linkToggle() {

  if (config === "links" || !config) {
    toggle.click();
    showMagnets();
    toggle.checked = true;
    localStorage.setItem("linkToggle", "links");
  } else {
    toggle.click();
    showLinks();
    toggle.checked = false;
    localStorage.setItem("linkToggle", "magnets");
  }
}

function showLinks() {
  var expectedHtml = linkCss;
  if (toggleCss.textContent !== expectedHtml) {
    toggleCss.textContent = expectedHtml;
  }
  localStorage.setItem("linkToggle", "links");
  removeMagnetListeners();
  toggle.click();
  toggle.addEventListener("click", linkToggle, true);
}

function showMagnets() {
  var expectedHtml = magnetCss + magnetBtn;
  if (toggleCss.innerHTML !== expectedHtml) {
    toggleCss.innerHTML = expectedHtml;
  }
  localStorage.setItem("linkToggle", "magnets");
  attachMagnetListeners();
  magnetToClipboard();
  toggle.click();
  toggle.addEventListener("click", linkToggle, true);
}

function magnetToClipboard() {
  var toastPosition = toast.getBoundingClientRect().top;

  function fixToast() {
    var toastRect = toast.getBoundingClientRect();
    var parentRect = window.parent.document.documentElement.getBoundingClientRect();
    if (toastRect.bottom > parentRect.bottom || toastRect.top < parentRect.top) {
      var toastTop = Math.max(toastRect.top, parentRect.top);
      toast.style.position = "fixed";
      toast.style.top = toastTop - parentRect.top + "px";
    }
  }

  removeMagnetListeners();
  window.addEventListener("scroll", fixToast);
  window.addEventListener("resize", fixToast);
  if (window.frameElement) {
    window.parent.addEventListener("scroll", fixToast);
    window.parent.addEventListener("resize", fixToast);
  }

}

function removeMagnetListeners() {

  function noop() {}

  window.removeEventListener("scroll", fixToast);
  window.removeEventListener("resize", fixToast);

  if (window.frameElement) {
    window.parent.removeEventListener("scroll", fixToast);
    window.parent.removeEventListener("resize", fixToast);
  }

  function fixToast() {}

  for (let i = 0; i < magnets.length; i++) {
    let copyBtn = magnets[i].parentElement.querySelector(".copyMagnet");
    let newCopyBtn = copyBtn.cloneNode(true);
    copyBtn.parentNode.replaceChild(newCopyBtn, copyBtn);
  }
}

function attachMagnetListeners() {
  let toastTimeoutId;
  removeMagnetListeners();
  for (let i = 0; i < magnets.length; i++) {
    let anchor = magnets[i];
    let copyBtn = anchor.parentElement.querySelector(".copyMagnet");
    copyBtn.addEventListener("click", function(event) {
      event.preventDefault();
      toast.removeAttribute("hidden");
      toast.style.display = "block";
      let link = anchor.href;
      let hashRegex = link.match(/btih:([^&]*)/);
      let magnetHash = "";
      if (hashRegex) {magnetHash = hashRegex[1];}
      let magnetName = link.substring(link.lastIndexOf("=") + 1);
      magnetName = decodeURIComponent(magnetName);
      let tempInput = document.createElement("input");
      tempInput.value = link;
      document.body.appendChild(tempInput);
      tempInput.select();
      document.execCommand("copy");
      document.body.removeChild(tempInput);
      toast.innerHTML = "Magnet link copied to clipboard: <b>" + magnetName + "</b>" +
                        (magnetHash != "" ? "<br>Hash: <b>" + magnetHash + "</b>": "");
      console.log("Magnet link copied to clipboard: " + link);
      clearTimeout(toastTimeoutId);
      toastTimeoutId = setTimeout(function() {
        toast.style.display = "none";
        toast.textContent = "";
      }, 3500);
    });
  }
  toggle.addEventListener("click", linkToggle, true);
}

document.addEventListener('DOMContentLoaded', () => {initLinkToggler();});

export {initLinkToggler, linkToggle, magnetToClipboard, attachMagnetListeners};