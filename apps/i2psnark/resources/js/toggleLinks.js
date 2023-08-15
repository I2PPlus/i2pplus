function initLinkToggler() {
  var config = localStorage.getItem("linkToggle");
  var mlinks = document.querySelectorAll("#snarkTbody .magnet");
  var linkButtons = ".magnetlink,.torrentink a,.torrentink img,.trackerLink a,.trackerLink img,#linkswitch{text-align:center;border:0;box-shadow:none;background:none}" +
                    ".trackerLink,.magnet{padding:2px;text-align:center}" +
                    "#torrents .magnetlink,#torrents .magnetlink a,#torrents .magnetlink img,#torrents .trackerlink{margin:0;padding:0;white-space:normal;border:0;box-shadow:none;background:none}";
  var showLinks =   "#torrents #linkswitch::before{background:url(/i2psnark/.resources/icons/link.svg) no-repeat center center/20px!important}" +
                    "#snarkTbody .magnet,#snarkTbody .magnet:empty{padding:0;width:0;font-size:0}#snarkTbody .magnet>*{display:none}" +
                    "#snarkTbody .trackerLink{padding:0 4px;width:1%;vertical-align:middle;text-align:center;font-size:0}#snarkTbody .trackerLink>*{display:inline-block}" +
                    "#torrents td.trackerLink img{margin:0;width:18px!important;height:18px!important}" +
                    "@media(min-width:1500px){#torrents td.trackerLink img{margin:0;width:20px!important;height:20px!important}";
  var showMagnets = "#torrents #linkswitch::before{background:url(/i2psnark/.resources/icons/magnet.svg) no-repeat center center/20px!important}" +
                    "#snarkTbody .trackerLink,#snarkTbody .trackerLink:empty{padding:0;width:0;font-size:0}#snarkTbody .trackerLink>*{display:none}" +
                    "#snarkTbody .magnet{padding:0 4px;width:1%;vertical-align:middle;text-align:center;font-size:0}#snarkTbody .magnet>*{display:inline-block}" +
                    "#torrents td.trackerLink img{margin:0;width:18px!important;height:18px!important}" +
                    "@media(min-width:1500px){#torrents td.magnet img{margin:0;width:20px!important;height:20px!important}}";
  var showSwitch =  "#linkswitch{display:inline-block!important;background:none}" +
                    "#torrents #linkswitch::before,#linkswitch:not(:checked)::before{width:20px;height:20px;display:inline-block;position:relative;top:calc(50% - 10px);" +
                    "left:calc(50% - 10px);transform:none!important;border:0;box-shadow:none;content:''!important}" + linkButtons;
  var tlinks = document.querySelectorAll("#snarkTbody .trackerLink");
  var toggle = document.getElementById("linkswitch");
  var toggleCss = document.getElementById("toggleLinks");

  function initToggle() {
    if (toggle !== null) {
      toggle.removeAttribute("hidden");
      if (config === null || config === "magnets") {
        localStorage.setItem("linkToggle", "magnets");
        toggle.checked = true;
        toggle.setAttribute("checked", "checked");
        toggleCss.innerHTML = showSwitch + showMagnets;
      } else {
        localStorage.setItem("linkToggle", "links");
        toggle.checked = false;
        toggle.setAttribute("checked", "");
        toggleCss.innerHTML = showSwitch + showLinks;
      }
    }
  }
  initToggle();
  toggle.addEventListener("click", linkToggle, true);
}

function linkToggle() {
  var config = window.localStorage.getItem("linkToggle");
  var mlinks = document.querySelectorAll("#snarkTbody .magnet");
  var linkButtons = ".magnetlink,.torrentink a,.torrentink img,.trackerLink a,.trackerLink img,#linkswitch{text-align:center;border:0;box-shadow:none;background:none}" +
                    ".trackerLink,.magnet{padding:2px;text-align:center}" +
                    "#torrents .magnetlink,#torrents .magnetlink a,#torrents .magnetlink img,#torrents .trackerlink{margin:0;padding:0;white-space:normal;border:0;box-shadow:none;background:none}";
  var showLinks =   "#torrents #linkswitch::before{background:url(/i2psnark/.resources/icons/link.svg) no-repeat center center/20px!important}" +
                    "#snarkTbody .magnet,#snarkTbody .magnet:empty{padding:0;width:0;font-size:0}#snarkTbody .magnet>*{display:none}" +
                    "#snarkTbody .trackerLink{padding:4px;width:1%;vertical-align:middle;text-align:center;font-size:0}#snarkTbody .trackerLink>*{display:inline-block}" +
                    "#torrents td.trackerLink img{margin:0;width:18px!important;height:18px!important}" +
                    "@media(min-width:1500px){#torrents td.trackerLink img{margin:0;width:20px!important;height:20px!important}";
  var showMagnets = "#torrents #linkswitch::before{background:url(/i2psnark/.resources/icons/magnet.svg) no-repeat center center/20px!important}" +
                    "#snarkTbody .trackerLink,#snarkTbody .trackerLink:empty{padding:0;width:0;font-size:0}#snarkTbody .trackerLink>*{display:none}" +
                    "#snarkTbody .magnet{padding:4px;width:1%;vertical-align:middle;text-align:center;font-size:0}#snarkTbody .magnet>*{display:inline-block}" +
                    "#torrents td.trackerLink img{margin:0;width:18px!important;height:18px!important}" +
                    "@media(min-width:1500px){#torrents td.magnet img{margin:0;width:20px!important;height:20px!important}}";
  var showSwitch =  "#linkswitch{display:inline-block!important;background:none}" +
                    "#torrents #linkswitch::before,#linkswitch:not(:checked)::before{width:20px;height:20px;display:inline-block;position:relative;top:calc(50% - 10px);" +
                    "left:calc(50% - 10px);transform:none!important;border:0;box-shadow:none;content:''!important}" +
                    showLinks + linkButtons;
  var tlinks = document.querySelectorAll("#snarkTbody .trackerLink");
  var toggle = document.getElementById("linkswitch");
  var toggleCss = document.getElementById("toggleLinks");
  if (toggle !== null) {
    if (config === "links" || config === null) {
      //console.log("LinkToggle set to magnets");
      localStorage.setItem("linkToggle", "magnets");
      toggle.checked = false;
      toggleCss.innerHTML = showSwitch + showMagnets;
    } else {
      //console.log("LinkToggle set to links");
      localStorage.setItem("linkToggle", "links");
      toggle.checked = true;
      toggleCss.innerHTML = showSwitch + showLinks;
    }
  }
}

export {initLinkToggler, linkToggle};