/*  I2PSnark refreshTorrents.js by dr|3d */
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

function initLinkToggler() {
  var config = localStorage.getItem("linkToggle");
  var mlinks = document.querySelectorAll("#snarkTbody .magnet");
  var tlinks = document.querySelectorAll("#snarkTbody .trackerLink");
  var toggle = document.getElementById("linkswitch");

  if (toggle) {
    toggle.removeAttribute("hidden");
    if (config === null || config === "magnets") {
      localStorage.setItem("linkToggle", "magnets");
      toggle.checked = true;
      toggle.setAttribute("checked", "checked");
    } else {
      localStorage.setItem("linkToggle", "links");
      toggle.checked = false;
      toggle.setAttribute("checked", "");
    }
    toggle.addEventListener("click", linkToggle, true);
  }
}

function linkToggle() {
  var config = window.localStorage.getItem("linkToggle");
  var mlinks = document.querySelectorAll("#snarkTbody .magnet");
  var tlinks = document.querySelectorAll("#snarkTbody .trackerLink");
  var toggle = document.getElementById("linkswitch");
  var toggleCss = document.getElementById("toggleLinks");

  if (toggle !== null) {
    if (config === "links" || config === null) {
      localStorage.setItem("linkToggle", "magnets");
      toggle.checked = false;

      const expectedHtml = showMagnets;
      if (toggleCss.innerHTML !== expectedHtml) {
        toggleCss.innerHTML = expectedHtml;
      }

    } else {
      localStorage.setItem("linkToggle", "links");
      toggle.checked = true;

      const expectedHtml = showLinks;
      if (toggleCss.innerHTML !== expectedHtml) {
        toggleCss.innerHTML = expectedHtml;
      }

    }
  }
}

export {initLinkToggler, linkToggle};