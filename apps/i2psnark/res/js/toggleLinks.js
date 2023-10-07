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

const magnetBtn =  "#snarkTbody .magnetlink{position:relative}#snarkTbody .copyMagnet{width:100%;height:100%;position:absolute;top:0;right:0;bottom:0;left:0;" +
                   "border:0}";

const magnets = document.querySelectorAll("#snarkTbody td.magnet .magnetlink");
const main = document.getElementById("mainsection");
const mlinks = document.querySelectorAll("#snarkTbody .magnet");
const tlinks = document.querySelectorAll("#snarkTbody .trackerLink");
const toast = document.querySelector("#toast");
const toggleAttached = main.querySelector(".toggleListenerAttached");
const toggleCss = document.getElementById("toggleLinks");
const toggle = document.getElementById("linkswitch");
const torrents = document.getElementById("torrents");
const torrentlist = document.getElementById("torrentlist");

let config;
let debugging = true;
let toastTimeoutId = null;

function initLinkToggler() {
  config = localStorage.getItem("linkToggle");
  if (!toggle) {
    console.log("Couldn't find #linkswitch in DOM");
    return;
  }

  removeToggleListener();

  if (!main.classList.contains("toggleListenerAttached")) {
    attachToggleListener();
    main.classList.add("toggleListenerAttached");
    console.log("toggleListenerAttached class not present -> toggle listener attached");
  }

/**
  if (config === "links") {
    showLinks();
  } else if (config === "magnets" || !config) {
    showMagnets();
  }
**/
}

function attachToggleListener() {
  if (!main.classList.contains("toggleListenerAttached")) {
    torrentlist.addEventListener("click", toggleHandler);
    torrentlist.classList.add("toggleListenerAttached");
  }
}

function removeToggleListener() {
  if (toggleAttached) {
    const events = main._events?.click || [];
    // Start loop from index 1 to leave the first event listener intact
    for (let i = 1; i < events.length; i++) {
      torrentlist.removeEventListener("click", events[i]);
    }
  }
}

function toggleHandler(event) {
  if (!event.target.matches("#linkswitch")) {
    console.log("Couldn't find #linkswitch in DOM");
    return;
  } else if (event.target.matches("#linkswitch")) {
    linkToggle();
  }
  console.log("Detected click on link toggler");
}

function linkToggle() {
  if (config === "links") {
    showMagnets();
  } else if (config === "magnets" || !config) {
    showLinks();
  }
}

function showLinks() {
  toggle.checked = false;
  toggleCss.textContent = linkCss;
  localStorage.setItem("linkToggle", "links");
  config = "links";
}

function showMagnets() {
  toggle.checked = true;
  toggleCss.textContent = magnetCss + magnetBtn;
  config = "magnets";
  localStorage.setItem("linkToggle", "magnets");
}

function attachMagnetListeners() {
  if (!toggle) {
    console.log("Couldn't find #linkswitch in DOM, not attaching magnet listeners");
    return;
  }

  function copiedToClipboard(event, anchor) {
    clearTimeout(toastTimeoutId);
    event.preventDefault();
    toast.removeAttribute("hidden");
    toast.style.display = "block";
    let link = anchor;
    if (!link || !link.startsWith("magnet:?xt=urn:btih:")) {
      console.log("Magnet link not valid");
      return;
    } else {
      console.log("Magnet link: " + link + " copied to clipboard");
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
    console.log("Magnet link copied to clipboard: " + link);
    toastTimeoutId = setTimeout(function() {
      toast.style.display = "none";
      toast.textContent = "";
    }, 3500);
  }

  main.addEventListener("click", function copyMagnetHandler(event) {
    if (event.target.matches(".copyMagnet")) {
      if (!event.target.hasAttribute("data-click")) {
         event.target.setAttribute("data-click", true);
        const anchor = event.target.parentNode.href;
        console.log("anchor reported as: " + anchor);
        if (toastTimeoutId) {
          clearTimeout(toastTimeoutId);
        }
        copiedToClipboard(event, anchor);
      }
    }
  });
}

document.addEventListener("DOMContentLoaded", () => {
  initLinkToggler();
  if (config === "links") {
    //toggle.checked = false;
  } else if (config === "magnets" || !config) {
    //toggle.click();
    toggle.click();
    showMagnets();
    //toggle.checked = true;
  }
  attachMagnetListeners();
});

export {initLinkToggler, linkToggle};