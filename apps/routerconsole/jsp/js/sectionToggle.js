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
  const xhr = document.getElementById("xhr");
  const savedConfigs = localStorage.getItem("sidebarSections");
  const sidebarSections = savedConfigs !== null ? JSON.parse(localStorage.getItem("sidebarSections")) : null;

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

  let listenersAdded = false;

  function initializeLocalStorage() {
    return new Promise((resolve) => {
      const defaultState = {
        advancedgeneral: false,
        advanced: false,
        bandwidth: false,
        general: false,
        help: false,
        internals: false,
        localtunnels: false,
        newsheadings: false,
        peers: false,
        queue: false,
        services: false,
        tunnels: false,
        updatesection: false
      };
      localStorage.setItem("sidebarSections", JSON.stringify(defaultState));
      resolve(defaultState);
    });
  }

  async function saveToggleStates() {
    const toggleInput = document.activeElement;
    if (toggleInput && toggleInput.id.startsWith("toggle_sb_")) {
      const key = toggleInput.id.replace("toggle_sb_", "");
      if (!savedConfigs) {await initializeLocalStorage();}
      sidebarSections[key] = toggleInput.checked;
      localStorage.setItem("sidebarSections", JSON.stringify(sidebarSections));
    }
  }

  function restoreToggleStates() {
    const sidebarSections = JSON.parse(localStorage.getItem("sidebarSections"));
    if (sidebarSections) {
      Object.entries(sidebarSections).forEach(([id, checked]) => {
        const toggleInput = document.getElementById(`toggle_sb_${id}`);
        if (toggleInput) {
          toggleInput.checked = checked;
          toggleElementVisibility(toggleInput, checked);
          const h3Element = toggleInput.closest("h3");
          const badge = toggleInput.parentElement.querySelector("a .badge");
          if (h3Element) {
            if (checked) {
              h3Element.classList.remove("collapsed");
              if (badge) {requestAnimationFrame(() => { badge.hidden = true;} )};
            }
            else {
              h3Element.classList.add("collapsed");
              if (badge) {badge.removeAttribute("hidden");}
            }
          }
        }
      });
    }
  }

  function addToggleListeners() {
    if (listenersAdded) {return;}
    sb_wrap.addEventListener("click", function (event) {
      if (event.target.id in toggleElements) {
        const currentState = event.target.checked;
        toggleElementVisibility(event.target, currentState);
        saveToggleStates();
      }
    });
    listenersAdded = true;
  }

  function toggleElementVisibility(toggleInput, isVisible) {
    const element = toggleElements[toggleInput.id];
    const titleH3 = element.parentElement.querySelector("h3");
    const badge = element.parentElement.querySelector("a .badge");
    if (!element) { return; }
    element.hidden = !isVisible;
    const hr = sb.querySelector(`#${element.id}+hr`);

    if (element === sb_updatesection) { handleUpdateSectionVisibility(isVisible); }
    else if (element === sb_services) { handleServicesVisibility(isVisible); }
    else if (isVisible) {
      if (hr) {
        hr.hidden = false;
        hr.style.display = null;
      }
      toggleInput.checked = true;
      if (element.classList) element.classList.remove("collapsed");
    } else {
      if (hr) {
        hr.hidden = true;
        hr.style.display = "none";
      }
      toggleInput.checked = false;
      if (element.classList) element.classList.add("collapsed");
    }

    if (toggleInput.id === "toggle_sb_localtunnels") { handleLocalTunnelsVisibility(isVisible); }
    else if (toggleInput.id === "toggle_sb_queue") { handleQueueVisibility(isVisible); }
    else if (toggleInput.id === "toggle_sb_tunnels") { handleTunnelsVisibility(isVisible); }

    if (element === sb_bandwidth && sb_graphstats) {
      sb_graphstats.style.opacity = sb_bandwidth.hidden ? "1" : null;
    }

    if (element === sb_internals || element === sb_advanced) {
      hr.hidden = false;
      hr.style.display = null;
    }
  }

  function handleServicesVisibility(isVisible) {
    if (sb_services !== null) {
      const hr = sb.querySelector("#sb_services.collapsed+hr");
      const toggleInput = document.getElementById("toggle_sb_services");
      const icons = sb_services.querySelectorAll(".sb_icon");
      const textLinks = sb_services.querySelectorAll("a:not(.sb_icon)");
      if (isVisible) {
        icons.forEach(icon => { icon.hidden = true });
        textLinks.forEach(link => { link.hidden = null });
        toggleInput.checked = true;
        sb_services.classList.remove("collapsed");
        toggleInput.closest("h3").classList.remove("collapsed");
        if (hr !== null) { hr.hidden = null; }
      } else {
        icons.forEach(icon => { icon.hidden = null });
        textLinks.forEach(link => { link.hidden = true })
        toggleInput.checked = false;
        sb_services.classList.add("collapsed");
        toggleInput.closest("h3").classList.add("collapsed");
        if (hr !== null) { hr.hidden = true; }
      }
    }
  }

  function handleUpdateSectionVisibility(isVisible) {
    if (sb_updatesection !== null) {
      sb_updatesection.hidden = false;
      const toggleInput = document.getElementById("toggle_sb_updatesection");
      if (isVisible) {
        toggleInput.checked = true;
        sb_updatesection.classList.remove("collapsed");
        sb_updatesection.querySelector("h3").classList.remove("collapsed");
      } else {
        toggleInput.checked = false;
        sb_updatesection.classList.add("collapsed");
        sb_updatesection.querySelector("h3").classList.add("collapsed");
      }
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
      if (isVisible) { localtunnelSummary.hidden = true; }
      else {
        localtunnelSummary.hidden = false;
        localtunnelSummary.outerHTML = summaryTable;
      }
    }
  }

  function handleQueueVisibility(isVisible) {
    if (jobBadge) { jobBadge.hidden = isVisible; }
  }

  function handleTunnelsVisibility(isVisible) {
    if (tunnelsBadge) { tunnelsBadge.hidden = isVisible; }
  }
  restoreToggleStates();
  addToggleListeners();

  document.addEventListener("DOMContentLoaded", () => {
    xhr.classList.add("fadein");
    setTimeout(() => { xhr.classList.remove("fadein"); }, 120);
  });

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