/* I2P+ SectionToggler by dr|z3d */
/* License: AGPLv3 or later */

function sectionToggler() {

  const jobBadge = document.querySelector("#sidebar h3 a[href=\"/jobs\"] .badge");
  const tunnelsBadge = document.querySelector("#sidebar h3 a[href=\"/tunnels\"] .badge");
  const localtunnelSummary = document.getElementById("localtunnelSummary");
  const sb_advanced = document.getElementById("sb_advanced");
  const sb_advancedgeneral = document.getElementById("sb_advancedgeneral");
  const sb_bandwidth = document.getElementById("sb_bandwidth");
  const sb_general = document.getElementById("sb_general");
  const sb_internals = document.getElementById("sb_internals");
  const sb_localtunnels = document.getElementById("sb_localtunnels");
  const sb_newsH3 = document.getElementById("sb_newsH3");
  const sb_newsheadings = document.getElementById("sb_newsheadings");
  const sb_peers = document.getElementById("sb_peers");
  const sb_peers_condensed = document.getElementById("sb_peers_condensed");
  const sb_queue = document.getElementById("sb_queue");
  const sb_services = document.getElementById("sb_services");
  const sb_shortgeneral = document.getElementById("sb_shortgeneral");
  const sb_tunnels = document.getElementById("sb_tunnels");
  const sb_tunnels_condensed = document.getElementById("sb_tunnels_condensed");
  const sb_updatesection = document.getElementById("sb_updatesection");

  function toggle_updatesection() {
    if (sb_updatesection !== null) {
      if (document.getElementById("toggle_sb_updatesection").checked !== true) {
        hide_updatesection();
      } else {
        show_updatesection();
      }
    }
  }

  function hide_updatesection() {
    if (sb_updatesection !== null && document.getElementById("toggle_sb_updatesection") !== null) {
      if (sb_updatesection.hidden !== true) {
        sb_updatesection.classList.add("collapsed");
        document.querySelector("#sb_updatesection > h3").classList.add("collapsed");
      }
      document.getElementById("toggle_sb_updatesection").checked = false;
      localStorage["section_updatesection"] = "hide";
    }
  }

  function show_updatesection() {
    if (sb_updatesection !== null && document.getElementById("toggle_sb_updatesection") !== null) {
      sb_updatesection.classList.remove("collapsed");
      if (document.querySelector("#sb_updatesection > h3") !== null) {
        document.querySelector("#sb_updatesection > h3").classList.remove("collapsed");
      }
      document.getElementById("toggle_sb_updatesection").checked = true;
      localStorage.removeItem("section_updatesection");
    }
  }

  function toggle_general() {
    if (document.getElementById("toggle_sb_general").checked !== true) {
      hide_general();
    } else {
      show_general();
    }
  }

  function hide_general() {
    if (sb_general !== null) {
      sb_general.hidden = true;
      document.querySelector("#sb_general + hr").hidden = true;
      document.getElementById("toggle_sb_general").checked = false;
      document.querySelector("#sidebar h3 a[href=\"/info\"] .badge").hidden = null;
      localStorage["section_general"] = "hide";
    }
  }

  function show_general() {
    if (sb_general !== null) {
      sb_general.hidden = null;
      document.querySelector("#sb_general + hr").hidden = null;
      document.getElementById("toggle_sb_general").checked = true;
      document.querySelector("#sidebar h3 a[href=\"/info\"] .badge").hidden = true;
      localStorage.removeItem("section_general");
    }
  }

  function toggle_advancedgeneral() {
    if (document.getElementById("toggle_sb_advancedgeneral").checked !== true) {
      hide_advancedgeneral();
    } else {
      show_advancedgeneral();
    }
  }

  function hide_advancedgeneral() {
    if (sb_advancedgeneral !== null) {
      sb_advancedgeneral.hidden = true;
      document.querySelector("#sb_advancedgeneral + hr").hidden = true;
      document.getElementById("toggle_sb_advancedgeneral").checked = false;
      document.querySelector("#sidebar h3 a[href=\"/info\"] .badge").hidden = null;
      localStorage["section_advancedgeneral"] = "hide";
    }
  }

  function show_advancedgeneral() {
    if (sb_advancedgeneral !== null) {
      sb_advancedgeneral.hidden = null;
      document.querySelector("#sb_advancedgeneral + hr").hidden = null;
      document.getElementById("toggle_sb_advancedgeneral").checked = true;
      document.querySelector("#sidebar h3 a[href=\"/info\"] .badge").hidden = true;
      localStorage.removeItem("section_advancedgeneral");
    }
  }

  function toggle_bandwidth() {
    if (document.getElementById("toggle_sb_bandwidth").checked !== true) {
      hide_bandwidth();
    } else {
      show_bandwidth();
    }
  }

  function hide_bandwidth() {
    if (sb_bandwidth !== null) {
      sb_bandwidth.hidden = true;
      if (document.querySelector("#sb_bandwidth + hr") !== null) {
        document.querySelector("#sb_bandwidth + hr").hidden = true;
        document.querySelector("#sb_bandwidth + hr").style.display = null;
      }
      document.getElementById("toggle_sb_bandwidth").checked = false;
      localStorage["section_bandwidth"] = "hide";
    }
  }

  function show_bandwidth() {
    if (sb_bandwidth !== null) {
      sb_bandwidth.hidden = null;
      if (document.querySelector("#sb_bandwidth + hr") !== null) {
        document.querySelector("#sb_bandwidth + hr").hidden = null;
        document.querySelector("#sb_bandwidth + hr").style.display = "block";
      }
      document.getElementById("toggle_sb_bandwidth").checked = true;
      localStorage.removeItem("section_bandwidth");
    }
  }

  function toggle_services() {
    if (document.getElementById("toggle_sb_services").checked !== true) {
      hide_services();
    } else {
      show_services();
    }
  }

  function hide_services() {
    if (sb_services !== null) {
      sb_services.hidden = true;
      document.getElementById("sb_services").classList.add("collapsed");
      document.getElementById("toggle_sb_services").checked = false;
      localStorage["section_services"] = "hide";
    }
  }

  function show_services() {
    if (sb_services !== null) {
      sb_services.hidden = null;
      if (document.querySelector("#sb_services.collapsed + hr") !== null) {
        document.querySelector("#sb_services.collapsed + hr").hidden = null;
      }
      document.getElementById("sb_services").classList.remove("collapsed");
      document.getElementById("toggle_sb_services").checked = true;
      localStorage.removeItem("section_services");
    }
  }

  function toggle_internals() {
    if (document.getElementById("toggle_sb_internals").checked !== true) {
      hide_internals();
    } else {
      show_internals();
    }
  }

  function hide_internals() {
    if (sb_internals !== null) {
      sb_internals.hidden = true;
      document.getElementById("sb_internals").classList.add("collapsed");
      document.getElementById("toggle_sb_internals").checked = false;
      localStorage["section_internals"] = "hide";
    }
  }

  function show_internals() {
    if (sb_internals !== null) {
      sb_internals.hidden = null;
      if (document.querySelector("#sb_internals.collapsed + hr") !== null) {
        document.querySelector("#sb_internals.collapsed + hr").hidden = null;
      }
      document.getElementById("sb_internals").classList.remove("collapsed");
      document.getElementById("toggle_sb_internals").checked = true;
      localStorage.removeItem("section_internals");
    }
  }

  function toggle_advanced() {
    if (document.getElementById("toggle_sb_advanced").checked !== true) {
      hide_advanced();
    } else {
      show_advanced();
    }
  }

  function hide_advanced() {
    if (sb_advanced !== null) {
      sb_advanced.hidden = true;
      document.getElementById("sb_advanced").classList.add("collapsed");
      document.getElementById("toggle_sb_advanced").checked = false;
      localStorage["section_advanced"] = "hide";
    }
  }

  function show_advanced() {
    if (sb_advanced !== null) {
      sb_advanced.hidden = null;
      if (document.querySelector("#sb_advanced.collapsed + hr") !== null) {
        document.querySelector("#sb_advanced.collapsed + hr").hidden = null;
      }
      document.getElementById("sb_advanced").classList.remove("collapsed");
      document.getElementById("toggle_sb_advanced").checked = true;
      localStorage.removeItem("section_advanced");
    }
  }

  function toggle_help() {
    if (document.getElementById("toggle_sb_help").checked !== true) {
      hide_help();
    } else {
      show_help();
    }
  }

  function hide_help() {
    if (sb_help !== null) {
      sb_help.hidden = true;
      document.getElementById("sb_help").classList.add("collapsed");
      document.getElementById("toggle_sb_help").checked = false;
      localStorage["section_help"] = "hide";
    }
  }

  function show_help() {
    if (sb_help !== null) {
      sb_help.hidden = null;
      if (document.querySelector("#sb_help.collapsed + hr") !== null) {
        document.querySelector("#sb_help.collapsed + hr").hidden = null;
      }
      document.getElementById("sb_help").classList.remove("collapsed");
      document.getElementById("toggle_sb_help").checked = true;
      localStorage.removeItem("section_help");
    }
  }

  function toggle_queue() {
    if (document.getElementById("toggle_sb_queue").checked !== true) {
      hide_queue();
    } else {
      show_queue();
    }
  }

  function hide_queue() {
    if (sb_queue !== null) {
      sb_queue.hidden = true;
      document.querySelector("#sb_queue + hr").hidden = true;
      document.getElementById("toggle_sb_queue").checked = false;
      if (jobBadge) {
        jobBadge.hidden = null;
      }
      localStorage["section_queue"] = "hide";
    }
  }

  function show_queue() {
    if (sb_queue !== null) {
      sb_queue.hidden = null;
      document.querySelector("#sb_queue + hr").hidden = null;
      document.getElementById("toggle_sb_queue").checked = true;
      if (jobBadge) {
        jobBadge.hidden = true;
      }
      localStorage.removeItem("section_queue");
    }
  }

  function toggle_tunnels() {
    if (document.getElementById("toggle_sb_tunnels").checked !== true && sb_tunnels !== null) {
      hide_tunnels();
    } else {
      show_tunnels();
    }
  }

  function hide_tunnels() {
    if (sb_tunnels !== null) {
      sb_tunnels.hidden = true;
      sb_tunnels_condensed.hidden = null;
      document.getElementById("toggle_sb_tunnels").checked = null;
      localStorage["section_tunnels"] = "hide";
      if (tunnelsBadge) {
        tunnelsBadge.hidden = null;
      }
    }
  }

  function show_tunnels() {
    if (sb_tunnels !== null) {
      sb_tunnels.hidden = null;
      sb_tunnels_condensed.hidden = true;
      document.getElementById("toggle_sb_tunnels").checked = true;
      localStorage.removeItem("section_tunnels");
      if (tunnelsBadge) {
        tunnelsBadge.hidden = true;
      }
    }
  }

  function toggle_peers() {
    if (document.getElementById("toggle_sb_peers").checked !== true) {
      hide_peers();
    } else {
      show_peers();
    }
  }

  function hide_peers() {
    if (sb_peers !== null) {
      sb_peers.hidden = true;
      sb_peers_condensed.hidden = null;
      document.getElementById("toggle_sb_peers").checked = false;
      document.querySelector("#sidebar h3 a[href=\"/peers\"] .badge").hidden = null;
      localStorage["section_peers"] = "hide";
    }
  }

  function show_peers() {
    if (sb_peers !== null) {
      sb_peers.hidden = null;
      sb_peers_condensed.hidden = true;
      document.getElementById("toggle_sb_peers").checked = true;
      document.querySelector("#sidebar h3 a[href=\"/peers\"] .badge").hidden = true;
      localStorage.removeItem("section_peers");
    }
  }

  function toggle_newsheadings() {
    if (document.getElementById("toggle_sb_newsheadings").checked !== true) {
      hide_newsheadings();
    } else {
      show_newsheadings();
    }
  }

  function hide_newsheadings() {
    if (sb_newsheadings !== null) {
      sb_newsheadings.hidden = true;
      sb_newsH3.classList.add("collapsed");
      document.querySelector("#sb_newsheadings + hr").hidden = true;
      document.getElementById("toggle_sb_newsheadings").checked = false;
      localStorage["section_newsheadings"] = "hide";
    }
  }

  function show_newsheadings() {
    if (sb_newsheadings !== null) {
      sb_newsheadings.hidden = null;
      sb_newsH3.classList.remove("collapsed");
      document.querySelector("#sb_newsheadings + hr").hidden = null;
      document.getElementById("toggle_sb_newsheadings").checked = true;
      localStorage.removeItem("section_newsheadings");
    }
  }

  function toggle_localtunnels() {
    if (document.getElementById("toggle_sb_localtunnels").checked !== true) {
      hide_localtunnels();
    } else {
      show_localtunnels();
    }
  }

  function hide_localtunnels() {
    if (sb_localtunnels !== null) {
      sb_localtunnels.hidden = true;
      sb_localtunnels.classList.add("collapsed");
      var clients = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/client.svg"]').length;
      var clientSpan = '<span id="clientCount" class="count_' + clients + '">' + clients + ' x <img src="/themes/console/images/client.svg"></span>';
      var pings = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/ping.svg"]').length;
      var pingSpan = '<span id="pingCount" class="count_' + pings + '">' + pings + ' x <img src="/themes/console/images/ping.svg"></span>';
      var servers = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/server.svg"]').length;
      var serverSpan = '<span id="serverCount" class="count_' + servers + '">' + servers + ' x <img src="/themes/console/images/server.svg"></span>';
      var snarks = document.querySelectorAll('#sb_localtunnels img[src="/themes/console/images/snark.svg"]').length;
      var snarkSpan = '<span id="snarkCount" class="count_' + snarks + '">' + snarks + ' x <img src="/themes/console/images/snark.svg"></span>';
      var summary = snarkSpan + " " + serverSpan + " " + clientSpan + " " + pingSpan;
      var summaryTable = '<table id="localtunnelSummary"><tr id="localtunnelsActive"><td>' + summary + '</td></tr></table>';
      if (localtunnelSummary !== null) {
        localtunnelSummary.hidden = null;
        localtunnelSummary.outerHTML = summaryTable;
      }
      document.getElementById("toggle_sb_localtunnels").checked = false;
      localStorage["section_localtunnels"] = "hide";
    }
  }

  function show_localtunnels() {
    if (localtunnelSummary !== null) {
      localtunnelSummary.hidden = true;
    }
    if (sb_localtunnels !== null) {
      sb_localtunnels.hidden = null;
    }
    document.getElementById("toggle_sb_localtunnels").checked = true;
    localStorage.removeItem("section_localtunnels");
  }

  function checkToggleStatus() {
    if (localStorage.getItem("section_advancedgeneral") !== null) {hide_advancedgeneral();} else {show_advancedgeneral();}
    if (localStorage.getItem("section_advanced") !== null) {hide_advanced();} else {show_advanced();}
    if (localStorage.getItem("section_bandwidth") !== null) {hide_bandwidth();} else {show_bandwidth();}
    if (localStorage.getItem("section_general") !== null) {hide_general();} else {show_general();}
    if (localStorage.getItem("section_help") !== null) {hide_general();} else {show_general();}
    if (localStorage.getItem("section_internals") !== null) {hide_internals();} else {show_internals();}
    if (localStorage.getItem("section_localtunnels") !== null) {hide_localtunnels();} else {show_localtunnels();}
    if (localStorage.getItem("section_peers") !== null) {hide_peers();} else {show_peers();}
    if (localStorage.getItem("section_queue") !== null) {hide_queue();} else {show_queue();}
    if (localStorage.getItem("section_services") !== null) {hide_services();} else {show_services();}
    if (localStorage.getItem("section_tunnels") !== null) {hide_tunnels();} else {show_tunnels();}
    if (localStorage.getItem("section_updatesection") !== null) {hide_updatesection();} else {show_updatesection();}
    if (localStorage.getItem("section_newsheadings") !== null) {hide_newsheadings();} else {show_newsheadings();}
  }

  function addToggleListeners() {
    const toggleElements = {
      "toggle_sb_advancedgeneral": toggle_advancedgeneral,
      "toggle_sb_advanced": toggle_advanced,
      "toggle_sb_bandwidth": toggle_bandwidth,
      "toggle_sb_general": toggle_general,
      "toggle_sb_help": toggle_help,
      "toggle_sb_internals": toggle_internals,
      "toggle_sb_localtunnels": toggle_localtunnels,
      "toggle_sb_newsheadings": toggle_newsheadings,
      "toggle_sb_peers": toggle_peers,
      "toggle_sb_queue": toggle_queue,
      "toggle_sb_services": toggle_services,
      "toggle_sb_tunnels": toggle_tunnels,
      "toggle_sb_updatesection": toggle_updatesection
    };

    for (const id in toggleElements) {
      const el = document.getElementById(id);
      if (el) {
        el.addEventListener("click", toggleElements[id]);
      }
    }
  }

  checkToggleStatus();
  addToggleListeners();

}

function countTunnels() {
  var tunnMan = document.getElementById("sb_localTunnelsHeading");
  var tunnelCount = document.querySelectorAll("img[src*=\"images/local_\"]");
  var tunnelsBadge = document.getElementById("tunnelCount");

  if (tunnMan && tunnelCount !== null) {
    var tunnelCountLength = tunnelCount.length;
    if (tunnelCountLength > 0 && tunnelsBadge.innerHTML !== tunnelCountLength) {
        tunnelsBadge.innerHTML = tunnelCountLength;
    } else {
      tunnelsBadge.innerHTML = "";
    }
  }
}

function countNewsItems() {
  if (document.getElementById("sb_newsheadings") !== null) {
    var doubleCount = document.querySelector("#newsCount + #newsCount");
    var newsBadge = document.getElementById("newsCount");
    var newsItemsLength = document.querySelectorAll("#sb_newsheadings table tr").length;
    if (newsItemsLength > 0) {
      newsBadge.hidden = null;
      if (newsBadge.innerHTML !== newsItemsLength) {
        newsBadge.innerHTML = newsItemsLength;
      }
    } else {
      newsBadge.hidden = false;
    }
    if (doubleCount) {
      doubleCount.remove();
    }
  }
}

export {sectionToggler, countTunnels, countNewsItems};