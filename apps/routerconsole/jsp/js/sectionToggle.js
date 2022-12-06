/* SectionToggler by dr|z3d */
/* License: AGPLv3 or later */

import {refreshSidebar} from "/js/refreshSidebar.js";

function sectionToggler() {

  function toggle_updatesection() {
    if (document.getElementById("sb_updatesection") !== null) {
      if (document.getElementById("toggle_sb_updatesection").checked != true) {
        hide_updatesection();
      } else {
        show_updatesection();
      }
    }
  }

  function hide_updatesection() {
    if (document.getElementById("sb_updatesection") !== null) {
      if (document.getElementById("sb_updatesection").hidden != true) {
        document.getElementById("sb_updatesection").classList.add("collapsed");
        document.querySelector("#sb_updatesection > h3").classList.add("collapsed");
      }
      document.getElementById("toggle_sb_updatesection").checked = false;
    }
    localStorage["section_updatesection"] = "hide";
  }

  function show_updatesection() {
    if (document.querySelector("#sb_updatesection") !== null) {
      document.querySelector("#sb_updatesection").classList.remove("collapsed");
      document.querySelector("#sb_updatesection > h3").classList.remove("collapsed");
      document.getElementById("toggle_sb_updatesection").checked = true;
    }
    localStorage.removeItem("section_updatesection");
  }

  function toggle_general() {
    if (document.getElementById("toggle_sb_general").checked != true) {
      hide_general();
    } else {
      show_general();
    }
  }

  function hide_general() {
    if (document.getElementById("sb_general") !== null) {
      document.getElementById("sb_general").hidden = true;
      document.querySelector("#sb_general + hr").hidden = true;
      document.getElementById("toggle_sb_general").checked = false;
      localStorage["section_general"] = "hide";
    }
  }

  function show_general() {
    if (document.getElementById("sb_general") !== null) {
      document.getElementById("sb_general").hidden = null;
      document.querySelector("#sb_general + hr").hidden = null;
      document.getElementById("toggle_sb_general").checked = true;
      localStorage.removeItem("section_general");
    }
  }

  function toggle_shortgeneral() {
    if (document.getElementById("toggle_sb_shortgeneral").checked != true) {
      hide_shortgeneral();
    } else {
      show_shortgeneral();
    }
  }

  function hide_shortgeneral() {
    if (document.getElementById("sb_shortgeneral") !== null) {
      document.getElementById("sb_shortgeneral").hidden = true;
      document.querySelector("#sb_shortgeneral + hr").hidden = true;
      document.getElementById("toggle_sb_shortgeneral").checked = false;
      localStorage["section_shortgeneral"] = "hide";
    }
  }

  function show_shortgeneral() {
    if (document.getElementById("sb_shortgeneral") !== null) {
      document.getElementById("sb_shortgeneral").hidden = null;
      document.querySelector("#sb_shortgeneral + hr").hidden = null;
      document.getElementById("toggle_sb_shortgeneral").checked = true;
      localStorage.removeItem("section_shortgeneral");
    }
  }

  function toggle_advancedgeneral() {
    if (document.getElementById("toggle_sb_advancedgeneral").checked != true) {
      hide_advancedgeneral();
    } else {
      show_advancedgeneral();
    }
  }

  function hide_advancedgeneral() {
    if (document.getElementById("sb_advancedgeneral") !== null) {
      document.getElementById("sb_advancedgeneral").hidden = true;
      document.querySelector("#sb_advancedgeneral + hr").hidden = true;
      document.getElementById("toggle_sb_advancedgeneral").checked = false;
      localStorage["section_advancedgeneral"] = "hide";
    }
  }

  function show_advancedgeneral() {
    if (document.getElementById("sb_advancedgeneral") !== null) {
      document.getElementById("sb_advancedgeneral").hidden = null;
      document.querySelector("#sb_advancedgeneral + hr").hidden = null;
      document.getElementById("toggle_sb_advancedgeneral").checked = true;
      localStorage.removeItem("section_advancedgeneral");
    }
  }

  function toggle_bandwidth() {
    if (document.getElementById("toggle_sb_bandwidth").checked != true) {
      hide_bandwidth();
    } else {
      show_bandwidth();
    }
  }

  function hide_bandwidth() {
    if (document.getElementById("sb_bandwidth") !== null) {
      document.getElementById("sb_bandwidth").hidden = true;
      if (document.querySelector("#sb_bandwidth + hr") !== null) {
        document.querySelector("#sb_bandwidth + hr").hidden = true;
        document.querySelector("#sb_bandwidth + hr").style.display = null;
      }
      document.getElementById("toggle_sb_bandwidth").checked = false;
      localStorage["section_bandwidth"] = "hide";
    }
  }

  function show_bandwidth() {
    if (document.getElementById("sb_bandwidth") !== null) {
      document.getElementById("sb_bandwidth").hidden = null;
      if (document.querySelector("#sb_bandwidth + hr") !== null) {
        document.querySelector("#sb_bandwidth + hr").hidden = null;
        document.querySelector("#sb_bandwidth + hr").style.display = "block";
      }
      document.getElementById("toggle_sb_bandwidth").checked = true;
      localStorage.removeItem("section_bandwidth");
    }
  }

  function toggle_services() {
    if (document.getElementById("toggle_sb_services").checked != true) {
      hide_services();
    } else {
      show_services();
    }
  }

  function hide_services() {
    if (document.getElementById("sb_services") !== null) {
      document.getElementById("sb_services").hidden = true;
      document.querySelector("#sb_services + hr").hidden = true;
      document.getElementById("toggle_sb_services").checked = false;
      localStorage["section_services"] = "hide";
    }
  }

  function show_services() {
    if (document.getElementById("sb_services") !== null) {
      document.getElementById("sb_services").hidden = null;
      document.querySelector("#sb_services + hr").hidden = null;
      document.getElementById("toggle_sb_services").checked = true;
      localStorage.removeItem("section_services");
    }
  }

  function toggle_internals() {
    if (document.getElementById("toggle_sb_internals").checked != true) {
      hide_internals();
    } else {
      show_internals();
    }
  }

  function hide_internals() {
    if (document.getElementById("sb_internals") !== null) {
      document.getElementById("sb_internals").hidden = true;
      document.querySelector("#sb_internals + hr").hidden = true;
      document.getElementById("toggle_sb_internals").checked = false;
      localStorage["section_internals"] = "hide";
    }
  }

  function show_internals() {
    if (document.getElementById("sb_internals") !== null) {
      document.getElementById("sb_internals").hidden = null;
      document.querySelector("#sb_internals + hr").hidden = null;
      document.getElementById("toggle_sb_internals").checked = true;
      localStorage.removeItem("section_internals");
    }
  }

  function toggle_advanced() {
    if (document.getElementById("toggle_sb_advanced").checked != true) {
      hide_advanced();
    } else {
      show_advanced();
    }
  }

  function hide_advanced() {
    if (document.getElementById("sb_advanced") !== null) {
      document.getElementById("sb_advanced").hidden = true;
      document.querySelector("#sb_advanced + hr").hidden = true;
      document.getElementById("toggle_sb_advanced").checked = false;
      localStorage["section_advanced"] = "hide";
    }
  }

  function show_advanced() {
    if (document.getElementById("sb_advanced") !== null) {
      document.getElementById("sb_advanced").hidden = null;
      document.querySelector("#sb_advanced + hr").hidden = null;
      document.getElementById("toggle_sb_advanced").checked = true;
      localStorage.removeItem("section_advanced");
    }
  }

  function toggle_queue() {
    if (document.getElementById("toggle_sb_queue").checked != true) {
      hide_queue();
    } else {
      show_queue();
    }
  }

  function hide_queue() {
    if (document.getElementById("sb_queue") !== null) {
      document.getElementById("sb_queue").hidden = true;
      document.querySelector("#sb_queue + hr").hidden = true;
      document.getElementById("toggle_sb_queue").checked = false;
      document.querySelector("#sidebar h3 a[href=\"/jobs\"] .badge").hidden = null;
      localStorage["section_queue"] = "hide";
    }
  }

  function show_queue() {
    if (document.getElementById("sb_queue") !== null) {
      document.getElementById("sb_queue").hidden = null;
      document.querySelector("#sb_queue + hr").hidden = null;
      document.getElementById("toggle_sb_queue").checked = true;
      document.querySelector("#sidebar h3 a[href=\"/jobs\"] .badge").hidden = true;
      localStorage.removeItem("section_queue");
    }
  }

  function toggle_tunnels() {
    if (document.getElementById("toggle_sb_tunnels").checked != true && document.getElementById("sb_tunnels") !== null) {
      hide_tunnels();
    } else {
      show_tunnels();
    }
  }

  function hide_tunnels() {
    if (document.getElementById("sb_tunnels") !== null) {
      document.getElementById("sb_tunnels").hidden = true;
      document.querySelector("#sb_tunnels + hr").hidden = true;
      document.getElementById("toggle_sb_tunnels").checked = null;
      document.querySelector("#sidebar h3 a[href=\"/tunnels\"] .badge").hidden = null;
      localStorage["section_tunnels"] = "hide";
    }
  }

  function show_tunnels() {
    if (document.getElementById("sb_tunnels") !== null) {
      document.getElementById("sb_tunnels").hidden = null;
      document.querySelector("#sb_tunnels + hr").hidden = null;
      document.getElementById("toggle_sb_tunnels").checked = true;
      document.querySelector("#sidebar h3 a[href=\"/tunnels\"] .badge").hidden = true;
      localStorage.removeItem("section_tunnels");
    }
  }

  function toggle_peers() {
    if (document.getElementById("toggle_sb_peers").checked != true) {
      hide_peers();
    } else {
      show_peers();
    }
  }

  function hide_peers() {
    if (document.getElementById("sb_peers") !== null) {
      document.getElementById("sb_peers").hidden = true;
      document.querySelector("#sb_peers + hr").hidden = true;
      document.getElementById("toggle_sb_peers").checked = false;
      document.querySelector("#sidebar h3 a[href=\"/peers\"] .badge").hidden = null;
      localStorage["section_peers"] = "hide";
    }
  }

  function show_peers() {
    if (document.getElementById("sb_peers") !== null) {
      document.getElementById("sb_peers").hidden = null;
      document.querySelector("#sb_peers + hr").hidden = null;
      document.getElementById("toggle_sb_peers").checked = true;
      document.querySelector("#sidebar h3 a[href=\"/peers\"] .badge").hidden = true;
      localStorage.removeItem("section_peers");
    }
  }

  function toggle_localtunnels() {
    if (document.getElementById("toggle_sb_localtunnels").checked != true) {
      hide_localtunnels();
    } else {
      show_localtunnels();
    }
  }

  function hide_localtunnels() {
    if (document.getElementById("sb_localtunnels") !== null) {
      document.getElementById("sb_localtunnels").hidden = true;
      document.getElementById("sb_localtunnels").classList.add("collapsed");
      var snarks = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/snark.svg"]').length;
      var servers = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/server.svg"]').length;
      var clients = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/client.svg"]').length;
      var pings = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/ping.svg"]').length;
      var snarkSpan = '<span id="snarkCount" class="count_"' + snarks + '">' + snarks + ' x <img src="/themes/console/images/snark.svg"></span>';
      var serverSpan = '<span id="serverCount" class="count_"' + servers + '">' + servers + ' x <img src="/themes/console/images/server.svg"></span>';
      var clientSpan = '<span id="clientCount" class="count_"' + clients + '">' + clients + ' x <img src="/themes/console/images/client.svg"></span>';
      var pingSpan = '<span id="pingCount" class="count_"' + pings + '">' + pings + ' x <img src="/themes/console/images/ping.svg"></span>';
      var summary = snarkSpan + " " + serverSpan + " " + clientSpan + " " + pingSpan;
      var sb_localtunnels = document.getElementById("sb_localtunnels");
      var summaryTable = '<table id="localtunnelSummary"><tr id="localtunnelsActive"><td>' + summary + '</td></tr></table>';
      var localTunnelSummary = document.getElementById("localtunnelSummary");
      if (document.getElementById("localtunnelSummary") == null) {
        sb_localtunnels.outerHTML += summaryTable;
      } else {
        localTunnelSummary.outerHTML = summaryTable;
      }
      document.getElementById("toggle_sb_localtunnels").checked = false;
      localStorage["section_localtunnels"] = "hide";
    }
  }

  function show_localtunnels() {
    if (document.getElementById("sb_localtunnels") !== null) {
      document.getElementById("sb_localtunnels").hidden = null;
      if (document.getElementById("localtunnelSummary") != null) {
        document.getElementById("localtunnelSummary").hidden = true;
      }
      document.getElementById("toggle_sb_localtunnels").checked = true;
      localStorage.removeItem("section_localtunnels");
    }
  }

  function checkToggleStatus() {
    if (localStorage["section_advancedgeneral"] !== null) {hide_advancedgeneral()}
    if (localStorage["section_advanced"] !== null) {hide_advanced()}
    if (localStorage["section_bandwidth"] !== null) {hide_bandwidth()}
    if (localStorage["section_general"] !== null) {hide_general()}
    if (localStorage["section_internals"] !== null) {hide_internals()}
    if (localStorage["section_localtunnels"] !== null) {hide_localtunnels()}
    if (localStorage["section_peers"] !== null) {hide_peers()}
    if (localStorage["section_queue"] !== null) {hide_queue()}
    if (localStorage["section_services"] !== null) {hide_services()}
    if (localStorage["section_shortgeneral"] !== null) {hide_shortgeneral()}
    if (localStorage["section_tunnels"] !== null) {hide_tunnels()}
    if (localStorage["section_updatesection"] !== null) {hide_updatesection()}
  }

  function addToggleListeners() {
    if (document.getElementById("toggle_sb_advancedgeneral")) {document.getElementById("toggle_sb_advancedgeneral").addEventListener("click", toggle_advancedgeneral)}
    if (document.getElementById("toggle_sb_advanced")) {document.getElementById("toggle_sb_advanced").addEventListener("click", toggle_advanced)}
    if (document.getElementById("toggle_sb_bandwidth")) {document.getElementById("toggle_sb_bandwidth").addEventListener("click", toggle_bandwidth)}
    if (document.getElementById("toggle_sb_general")) {document.getElementById("toggle_sb_general").addEventListener("click", toggle_general)}
    if (document.getElementById("toggle_sb_internals")) {document.getElementById("toggle_sb_internals").addEventListener("click", toggle_internals)}
    if (document.getElementById("toggle_sb_localtunnels")) {document.getElementById("toggle_sb_localtunnels").addEventListener("click", toggle_localtunnels)}
    if (document.getElementById("toggle_sb_peers")) {document.getElementById("toggle_sb_peers").addEventListener("click", toggle_peers)}
    if (document.getElementById("toggle_sb_queue")) {document.getElementById("toggle_sb_queue").addEventListener("click", toggle_queue)}
    if (document.getElementById("toggle_sb_services")) {document.getElementById("toggle_sb_services").addEventListener("click", toggle_services)}
    if (document.getElementById("toggle_sb_shortgeneral")) {document.getElementById("toggle_sb_shortgeneral").addEventListener("click", toggle_shortgeneral)}
    if (document.getElementById("toggle_sb_tunnels")) {document.getElementById("toggle_sb_tunnels").addEventListener("click", toggle_tunnels)}
    if (document.getElementById("toggle_sb_updatesection")) {document.getElementById("toggle_sb_updatesection").addEventListener("click", toggle_updatesection)}
  }
  addToggleListeners();
  document.addEventListener("pageshow", () => {
    checkToggleStatus();
  });
}

function countTunnels() {
  var tunnelCountLength = document.querySelectorAll("img[src*=\"images/local_\"]").length;
  if (document.querySelector("#sidebar h3 a[href=\"/i2ptunnelmgr\"]").innerHTML.indexOf(tunnelCountLength) == -1) {
    if (tunnelCountLength > 0) {
      var displayCount = " <span id=\"tunnelCount\">" + tunnelCountLength + "</span>";
      document.querySelector("#sidebar h3 a[href=\"/i2ptunnelmgr\"]").innerHTML += displayCount;
      var badgeCount = document.getElementById("tunnelCount").innerHTML;
      if (badgeCount != tunnelCountLength) {
        badgeCount = tunnelCountLength;
      }
    }
    var doubleCount = document.querySelector("#tunnelCount + #tunnelCount");
    if (doubleCount) {
      doubleCount.remove();
    }
  }
  //if (localTunnelSummary !== null) {
  //  localTunnelSummary.outerHTML = summaryTable;
  //}
}

export {sectionToggler, countTunnels};