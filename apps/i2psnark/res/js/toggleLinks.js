/* I2PSnark toggleLinks.js by dr|z3d */
/* Provide a toggle switch for magnets or links in the main torrent table */
/* License: AGPL3 or later */

"use strict";

const linkCss =    "#torrents #linkswitch::before{background:url(/i2psnark/.res/icons/link.svg) no-repeat center center/20px!important}" +
                   "#snarkTbody .magnet{padding:0;width:0;font-size:0}#snarkTbody .magnet>*{display:none!important}" +
                   "#snarkTbody .trackerLink{padding:4px;width:1%;vertical-align:middle;text-align:center;font-size:0}#snarkTbody .trackerLink>*{display:inline-block!important}" +
                   "#torrents td.trackerLink img{margin:0;width:18px!important;height:18px!important}" +
                   "@media(min-width:1500px){#torrents td.trackerLink img{margin:0;width:20px!important;height:20px!important}";

const magnetCss =  "#torrents #linkswitch::before{background:url(/i2psnark/.res/icons/magnet.svg) no-repeat center center/20px!important}" +
                   "#snarkTbody .trackerLink,#snarkTbody .trackerLink:empty{padding:0;width:0;font-size:0}#snarkTbody .trackerLink>*{display:none!important}" +
                   "#snarkTbody .magnet{padding:4px;width:1%;vertical-align:middle;text-align:center;font-size:0}#snarkTbody .magnet>*{display:inline-block!important}" +
                   "#torrents td.trackerLink img{margin:0;width:18px!important;height:18px!important}" +
                   "@media(min-width:1500px){#torrents td.magnet img{margin:0;width:20px!important;height:20px!important}}";

const magnetBtn =  "#snarkTbody .magnetlink{position:relative}#snarkTbody .copyMagnet{width:100%;height:100%;position:absolute;top:0;right:0;bottom:0;left:0;" +
                   "border:0}";

const magnets = document.querySelectorAll("#snarkTbody td.magnet .magnetlink");
const main = document.getElementById("mainsection");
const mlinks = document.querySelectorAll("#snarkTbody .magnet");
const tlinks = document.querySelectorAll("#snarkTbody .trackerLink");
if (main) {const toggleAttached = main.querySelector(".toggleListenerAttached");}
const toggle = document.getElementById("linkswitch");
const torrents = document.getElementById("torrents");
const torrentlist = document.getElementById("torrentlist");

let config;
let debugging = false;
let toastTimeoutId = null;
let toggleCss;

function initLinkToggler() {
  if (!toggle) {
    console.log("Couldn't find #linkswitch in DOM");
    return;
  }

  if (!main.classList.contains("toggleListenerAttached")) {
    attachToggleListener();
    main.classList.add("toggleListenerAttached");
    if (debugging) {
      console.log("toggleListenerAttached class not present -> toggle listener attached");
    }
  }
  config = localStorage.getItem("linkToggle");
  toggleCss = document.querySelector("#toggleLinks");
  if (config === "magnets" || !config) {
    toggleCss.textContent = magnetCss + magnetBtn;
    toggle.checked = true;
  } else {
    toggleCss.textContent = linkCss;
    toggle.checked = false;
  }
}

function attachToggleListener() {
  if (torrentlist && !main.classList.contains("toggleListenerAttached")) {
    torrentlist.addEventListener("click", toggleHandler);
    torrentlist.classList.add("toggleListenerAttached");
  }
}

function toggleHandler(event) {
  if (!event.target.matches("#linkswitch")) {return;}
  else {doToggle();}
  if (debugging) {console.log("Detected click on #linkswitch");}
}

function doToggle() {
  config = localStorage.getItem("linkToggle");
  toggleCss = document.querySelector("#toggleLinks");
  toggleCss.textContent = "";
  if (config === "magnets" || !config) {
    toggleCss.textContent = linkCss;
    toggle.checked = false;
    localStorage["linkToggle"] = "links";
  } else {
    toggleCss.textContent = magnetCss + magnetBtn;
    toggle.checked = true;
    localStorage["linkToggle"] = "magnets";
  }
  if (debugging) {console.log("#toggleLinks = " + toggleCss.innerHTML);}
}

function attachMagnetListeners() {
  if (!toggle) {
    if (debugging) {console.log("Couldn't find #linkswitch in DOM, not attaching magnet listeners");}
    return;
  }

  function copiedToClipboard(event, anchor) {
    const toast = document.getElementById("toast");
    clearTimeout(toastTimeoutId);
    event.preventDefault();
    toast.removeAttribute("hidden");
    toast.style.display = "block";
    let link = anchor;
    if (!link || !link.startsWith("magnet:?xt=urn:btih:")) {
      if (debugging) {console.log("Magnet link not valid");}
      return;
    } else {
      if (debugging) {console.log("Magnet link: " + link + " copied to clipboard");}
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
    toast.innerHTML = "Magnet link copied to clipboard" + (magnetName !== null ? ": <b> " +
                       magnetName + "</b>" : "") + "<br>Hash: <b>" + magnetHash + "</b>";
    if (debugging) {console.log("Magnet link copied to clipboard: " + link);}
    toastTimeoutId = setTimeout(function() {
      toast.style.display = "none";
      toast.textContent = "";
    }, 8000);
  }

  main.addEventListener("click", function copyMagnetHandler(event) {
    if (event.target.matches(".copyMagnet")) {
      if (!event.target.hasAttribute("data-click")) {
         event.target.setAttribute("data-click", true);
        const anchor = event.target.parentNode.href;
        if (debugging) {console.log("anchor reported as: " + anchor);}
        if (toastTimeoutId) {
          clearTimeout(toastTimeoutId);
        }
        copiedToClipboard(event, anchor);
      }
    }
  });
}

document.addEventListener("DOMContentLoaded", () => {
  config = localStorage.getItem("linkToggle");
  initLinkToggler();
  attachMagnetListeners();
});

export {initLinkToggler};