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
let config;
const magnets = document.querySelectorAll("#snarkTbody td.magnet .magnetlink");
const mlinks = document.querySelectorAll("#snarkTbody .magnet");
const tlinks = document.querySelectorAll("#snarkTbody .trackerLink");
const toast = document.querySelector("#toast");
const toggleCss = document.getElementById("toggleLinks");
const toggle = document.getElementById("linkswitch");

function initLinkToggler() {
  config = localStorage.getItem("linkToggle");

  if (!toggle) {
    return;
  }

  toggle.removeAttribute("hidden");
  toggle.removeAttribute("checked");
  toggle.addEventListener("click", linkToggle);

  if (config === "links") {
    showLinks();
  } else if (config === "magnets" || !config) {
    showMagnets();
  }
}

function linkToggle() {
  if (config === "links") {
    showMagnets();
  } else if (config === "magnets") {
    showLinks();
  }
}

function showLinks() {
  toggle.checked = true;
  toggleCss.textContent = linkCss;
  localStorage.setItem("linkToggle", "links");
  config = "links";
}

function showMagnets() {
  attachMagnetListeners();
  toggle.checked = false;
  toggleCss.textContent = magnetCss + magnetBtn;
  magnetToast();
  localStorage.setItem("linkToggle", "magnets");
  config = "magnets";
}

function magnetToast() {
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

  window.addEventListener("scroll", fixToast);
  window.addEventListener("resize", fixToast);
  if (window.frameElement) {
    window.parent.addEventListener("scroll", fixToast);
    window.parent.addEventListener("resize", fixToast);
  }

}

function attachMagnetListeners() {
  if (!toggle) {
    return;
  }
  let toastTimeoutId = null;
  const copiedToClipboard = function(anchor, copyBtn, event) {
    event.preventDefault();
    if (toastTimeoutId) {
      clearTimeout(toastTimeoutId);
    }
    toast.removeAttribute("hidden");
    toast.style.display = "block";
    let link = anchor.href;
    if (!link || !link.startsWith("magnet:?xt=urn:btih:")) {
      return;
    }
    let magnetHash = link.substring(link.indexOf(":") + 1, link.indexOf("&"));
    let magnetName = link.substring(link.lastIndexOf("=") + 1);
    magnetName = decodeURIComponent(magnetName);
    let tempInput = document.createElement("input");
    tempInput.value = link;
    document.body.appendChild(tempInput);
    tempInput.select();
    document.execCommand("copy");
    document.body.removeChild(tempInput);
    toast.innerHTML = "Magnet link copied to clipboard: <b>" + magnetName + "</b>" +
                      "<br>Hash: <b>" + magnetHash + "</b>";
    console.log("Magnet link copied to clipboard: " + link);
    copyBtn.removeEventListener("click", copiedToClipboard);
    toastTimeoutId = setTimeout(function() {
      toast.style.display = "none";
      toast.textContent = "";
    }, 3500);
  };
  let magnets = document.querySelectorAll(".magnetlink");
  for (let i = 0; i < magnets.length; i++) {
    let anchor = magnets[i];
    if (!anchor.href || !anchor.href.startsWith("magnet:?xt=urn:btih:")) {
      continue;
    }
    let copyBtn = anchor.querySelector(".copyMagnet");
    copyBtn.removeEventListener("click", copiedToClipboard);
    copyBtn.addEventListener("click", copiedToClipboard.bind(null, anchor, copyBtn), {once: true});
  }
}

document.addEventListener('DOMContentLoaded', () => {
  initLinkToggler();
  attachMagnetListeners();
  magnetToast();
});

export {initLinkToggler, linkToggle, magnetToast, attachMagnetListeners};