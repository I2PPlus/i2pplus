/* I2PSnark toggleLinks.js by dr|z3d */
/* Provide a toggle switch for magnets or links in the main torrent table */
/* License: AGPL3 or later */

const showLinks =   "#torrents #linkswitch::before{background:url(/i2psnark/.resources/icons/link.svg) no-repeat center center/20px!important}" +
                    "#snarkTbody .magnet,#snarkTbody .magnet:empty{padding:0;width:0;font-size:0}#snarkTbody .magnet>*{display:none}" +
                    "#snarkTbody .trackerLink{padding:4px;width:1%;vertical-align:middle;text-align:center;font-size:0}#snarkTbody .trackerLink>*{display:inline-block}" +
                    "#torrents td.trackerLink img{margin:0;width:18px!important;height:18px!important}" +
                    "@media(min-width:1500px){#torrents td.trackerLink img{margin:0;width:20px!important;height:20px!important}";

const showMagnets = "#torrents #linkswitch::before{background:url(/i2psnark/.resources/icons/magnet.svg) no-repeat center center/20px!important}" +
                    "#snarkTbody .trackerLink,#snarkTbody .trackerLink:empty{padding:0;width:0;font-size:0}#snarkTbody .trackerLink>*{display:none}" +
                    "#snarkTbody .magnet{padding:4px;width:1%;vertical-align:middle;text-align:center;font-size:0}#snarkTbody .magnet>*{display:inline-block}" +
                    "#torrents td.trackerLink img{margin:0;width:18px!important;height:18px!important}" +
                    "@media(min-width:1500px){#torrents td.magnet img{margin:0;width:20px!important;height:20px!important}}";

 const magnetBtn  = "#snarkTbody .magnetlink{position:relative}#snarkTbody .copyMagnet{width:100%;height:100%;position:absolute;top:0;right:0;bottom:0;left:0;" +
                    "border:0}";

const magnets = document.querySelectorAll("#snarkTbody td.magnet .magnetlink");
const toast = document.querySelector("#toast");

function initLinkToggler() {
  var config = localStorage.getItem("linkToggle");
  if (config !== null) {config = config.toString();}
  var mlinks = document.querySelectorAll("#snarkTbody .magnet");
  var tlinks = document.querySelectorAll("#snarkTbody .trackerLink");
  var toggle = document.getElementById("linkswitch");

  if (!toggle) {return;}
  toggle.removeAttribute("hidden");
  if (config === "links" || config === "") {
    localStorage.setItem("linkToggle", "links");
    toggle.checked = true;
    toggle.setAttribute("checked", "checked");
  } else {
    localStorage.setItem("linkToggle", "magnets");
    toggle.checked = false;
    toggle.setAttribute("checked", "");
    magnetToClipboard();
  }
  toggle.addEventListener("click", linkToggle, true);
  magnetToClipboard();
}

function linkToggle() {
  var config = window.localStorage.getItem("linkToggle").toString();
  var mlinks = document.querySelectorAll("#snarkTbody .magnet");
  var tlinks = document.querySelectorAll("#snarkTbody .trackerLink");
  var toggle = document.getElementById("linkswitch");
  var toggleCss = document.getElementById("toggleLinks");

  if (!toggle) {return;}
  if (config === "links" || config === "") {
    const expectedHtml = showMagnets + magnetBtn;
    if (toggleCss.innerHTML !== expectedHtml) {
      toggleCss.innerHTML = expectedHtml;
    }
    localStorage.setItem("linkToggle", "magnets");
    toggle.click();
  } else {
    const expectedHtml = showLinks + magnetBtn;
    if (toggleCss.innerHTML !== expectedHtml) {
      toggleCss.innerHTML = expectedHtml;
    }
    localStorage.setItem("linkToggle", "links");
    toggle.click();
  }
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

  window.addEventListener("scroll", fixToast);
  window.addEventListener("resize", fixToast);
  if (window.frameElement) {
    window.parent.addEventListener("scroll", fixToast);
    window.parent.addEventListener("resize", fixToast);
  }

  window.addEventListener("load", function() {
    for (var i = 0; i < magnets.length; i++) {
      var anchor = magnets[i];
      var span = anchor.parentElement.querySelector(".copyMagnet");
      span.style.display = "inline";
    }
  });
  attachMagnetListeners();
}

function attachMagnetListeners() {
  let toastTimeoutId;
  for (let i = 0; i < magnets.length; i++) {
    let anchor = magnets[i];
    let copyBtn = anchor.parentElement.querySelector(".copyMagnet");
    copyBtn.addEventListener("click", function(event) {
      event.preventDefault();
      toast.removeAttribute("hidden");
      toast.hidden=false;
      toast.style.display = "block";
      let link = anchor.href;
      let hashRegex = link.match(/btih:([^&]+)/);
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
      console.log("Link copied to clipboard: " + link);
      clearTimeout(toastTimeoutId);
      toastTimeoutId = setTimeout(function() {
        toast.removeAttribute("hidden");
        toast.hidden=true;
        toast.style.display = "none";
        toast.textContent = "";
      }, 3500);
    });
    copyBtn.addEventListener("click", function(event) {
      event.preventDefault();
      clearTimeout(toastTimeoutId);
      toastTimeoutId = setTimeout(function() {
        toast.removeAttribute("hidden");
        toast.hidden=true;
        toast.style.display = "none";
        toast.textContent = "";
      }, 3500);
    });
  }
}

export {initLinkToggler, linkToggle, magnetToClipboard, attachMagnetListeners};