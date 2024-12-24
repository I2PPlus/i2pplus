/* I2P+ sectionToggle.js by dr|z3d */
/* Sidebar section toggler for the I2P+ web console */
/* License: AGPLv3 or later */

function sectionToggler() {
  const sb = document.getElementById("sidebar");
  const jobBadge = sb.querySelector("h3 a[href=\"/jobs\"] .badge");
  const tunnelsBadge = sb.querySelector("h3 a[href=\"/tunnels\"] .badge");
  const localtunnelSummary = document.getElementById("localtunnelSummary");
  const sb_advanced = document.getElementById("sb_advanced");
  const sb_advancedgeneral = document.getElementById("sb_advancedgeneral");
  const sb_bandwidth = document.getElementById("sb_bandwidth");
  const sb_general = document.getElementById("sb_general");
  const sb_graphstats = document.getElementById("sb_graphstats");
  const sb_help = document.getElementById("sb_help");
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
  const sb_wrap = document.getElementById("sb_wrap") || sb;

  const toggleElements = {
    "toggle_sb_advancedgeneral": sb_advancedgeneral,
    "toggle_sb_advanced": sb_advanced,
    "toggle_sb_bandwidth": sb_bandwidth,
    "toggle_sb_general": sb_general,
    "toggle_sb_help": sb_help,
    "toggle_sb_internals": sb_internals,
    "toggle_sb_localtunnels": sb_localtunnels,
    "toggle_sb_newsheadings": sb_newsheadings,
    "toggle_sb_peers": sb_peers,
    "toggle_sb_queue": sb_queue,
    "toggle_sb_services": sb_services,
    "toggle_sb_tunnels": sb_tunnels,
    "toggle_sb_updatesection": sb_updatesection
  };

  function saveToggleStates() {
    const toggleState = {};
    Object.keys(toggleElements).forEach(id => {
      const toggleInput = document.getElementById(id);
      if (toggleInput) {
        const key = id.replace("toggle_sb_", "");
        toggleState[key] = toggleInput.checked;
      }
    });
    localStorage.setItem("sidebarSections", JSON.stringify(toggleState));
  }

  function restoreToggleStates() {
    const toggleStates = JSON.parse(localStorage.getItem("sidebarSections"));
    if (toggleStates) {
      Object.entries(toggleStates).forEach(([id, checked]) => {
        const toggleInput = document.getElementById("toggle_sb_" + id);
        if (toggleInput) {
          toggleInput.checked = checked;
          toggleElementVisibility(toggleInput, checked);
        }
      });
    }
  }

  function addToggleListeners() {
    sb_wrap.addEventListener("click", function(event) {
      if (event.target.id in toggleElements) {
        const currentState = event.target.checked;
        toggleElementVisibility(event.target, currentState);
        saveToggleStates();
      }
    });
  }

  function toggleElementVisibility(toggleInput, isVisible) {
    const element = toggleElements[toggleInput.id];
    const hr = sb.querySelector(`#${element.id}+hr`);

    if (element) {
      element.hidden = !isVisible;
      if (isVisible) {
        if (hr && element !== sb_services) {
          hr.hidden = false;
          hr.style.display = null;
        }
        toggleInput.checked = true;
        if (element.classList) element.classList.remove("collapsed");
      } else {
        if (hr && element !== sb_services) {
          hr.hidden = true;
          hr.style.display = "none";
        }
        toggleInput.checked = false;
        if (element.classList) element.classList.add("collapsed");
      }
      if (toggleInput.id === "toggle_sb_localtunnels") {
        handleLocalTunnelsVisibility(isVisible);
      } else if (toggleInput.id === "toggle_sb_queue") {
        handleQueueVisibility(isVisible);
      } else if (toggleInput.id === "toggle_sb_tunnels") {
        handleTunnelsVisibility(isVisible);
      }
      if (element === sb_bandwidth && sb_graphstats) {
        if (sb_bandwidth.hidden === true) {sb_graphstats.style.opacity = "1";}
        else {sb_graphstats.style.opacity = null;}
      } else if (element === sb_services) {
        if (sb_services.hidden === true) {sb_services.classList.add("collapsed");}
        else {sb_services.classList.remove("collapsed");}
      } else if (element === sb_help) {
        if (sb_help.hidden === true) {sb_help.classList.add("collapsed");}
        else {sb_help.classList.remove("collapsed");}
      } else if (element === sb_internals) {
        if (sb_internals.hidden === true) {sb_internals.classList.add("collapsed");}
        else {sb_internals.classList.remove("collapsed");}
      }
      if (element === sb_internals || element === sb_advanced) {
        hr.hidden = false;
        hr.style.display = null;
      }
    }
  }

  function hide_internals() {
    if (sb_internals !== null) {
      sb_internals.hidden = true;
      document.getElementById("sb_internals").classList.add("collapsed");
      document.getElementById("toggle_sb_internals").checked = false;
    }
  }

  function show_internals() {
    if (sb_internals !== null) {
      sb_internals.hidden = null;
      if (sb.querySelector("#sb_internals.collapsed+hr") !== null) {
        sb.querySelector("#sb_internals.collapsed+hr").hidden = null;
      }
      document.getElementById("sb_internals").classList.remove("collapsed");
      document.getElementById("toggle_sb_internals").checked = true;
    }
  }

  function handleLocalTunnelsVisibility(isVisible) {
      const clients = document.querySelectorAll("#sb_localtunnels img[src='/themes/console/images/client.svg']").length;
      const clientSpan = `<span id="clientCount" class="count_${clients}">${clients} x <img src='/themes/console/images/client.svg'></span>`;
      const i2pchats = document.querySelectorAll("#sb_localtunnels img[src='/themes/console/images/i2pchat.svg']").length;
      const i2pchatSpan = `<span id="i2pchatCount" class="count_${i2pchats}">${i2pchats} x <img src='/themes/console/images/i2pchat.svg'></span>`;
      const pings = document.querySelectorAll("#sb_localtunnels img[src='/themes/console/images/ping.svg']").length;
      const pingSpan = `<span id="pingCount" class="count_${pings}">${pings} x <img src='/themes/console/images/ping.svg'></span>`;
      const servers = document.querySelectorAll("#sb_localtunnels img[src='/themes/console/images/server.svg']").length;
      const serverSpan = `<span id="serverCount" class="count_${servers}">${servers} x <img src='/themes/console/images/server.svg'></span>`;
      const snarks = document.querySelectorAll("#sb_localtunnels img[src='/themes/console/images/snark.svg']").length;
      const snarkSpan = `<span id="snarkCount" class="count_${snarks}">${snarks} x <img src='/themes/console/images/snark.svg'></span>`;
      const summary = `${serverSpan} ${clientSpan} ${snarkSpan} ${i2pchatSpan} ${pingSpan}`;
      const summaryTable = `<table id="localtunnelSummary"><tr id="localtunnelsActive"><td>${summary}</td></tr></table>`;
      if (localtunnelSummary !== null) {
          if (isVisible) {localtunnelSummary.hidden = true;}
          else {
              localtunnelSummary.hidden = false;
              localtunnelSummary.outerHTML = summaryTable;
          }
      }
  }

  function handleQueueVisibility(isVisible) {
    if (jobBadge) {jobBadge.hidden = isVisible;}
  }

  function handleTunnelsVisibility(isVisible) {
    if (tunnelsBadge) {tunnelsBadge.hidden = isVisible;}
  }

  restoreToggleStates();
  addToggleListeners();
}

function countNewsItems() {
  const sb = document.getElementById("sidebar");
  const sbNewsHeadings = document.getElementById("sb_newsheadings");
  const newsBadge = document.getElementById("newsCount");
  const doubleCount = sb.querySelector("#newsCount+#newsCount");
  if (!sbNewsHeadings || !newsBadge) return;
  if (doubleCount) doubleCount.remove();
  const newsItemsLength = sbNewsHeadings.querySelectorAll("table tr").length;

  newsBadge.hidden = newsItemsLength <= 0;
  if (newsItemsLength > 0 && newsBadge.innerHTML !== newsItemsLength.toString()) {
    newsBadge.innerHTML = newsItemsLength;
  }
}

export { sectionToggler, countNewsItems };