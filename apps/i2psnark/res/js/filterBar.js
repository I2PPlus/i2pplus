/* I2P+ filterBar.js by dr|z3d */
/* Setup I2PSnark torrent display buttons so we can show/hide snarks
/* based on status and load filtered content via AJAX calls */
/* License: AGPL3 or later */

import {refreshTorrents, doRefresh} from "./refreshTorrents.js";

const torrents = document.getElementById("torrents");

let filterbar;
let observer;
let snarkCount;

async function showBadge() {
  const filterbar = document.getElementById("filterBar");
  if (!filterbar) {return;}

  const urlParams = new URLSearchParams(window.location.search);
  const filterQuery = urlParams.get("filter");
  const searchQuery = urlParams.get("search");
  const filterId = searchQuery !== null ? "search" : (filterQuery || "all");

  const allFilters = filterbar.querySelectorAll(".filter");

  const activeFilter = document.getElementById(filterId);
  const activeBadge = activeFilter.querySelector(".badge");
  activeBadge.id = "filtercount";
  if (activeFilter.id === "all") {
    activeBadge.hidden = false;
    const allBadge = filterbar.querySelector(".filter#all .badge");
  } else {activeBadge.hidden = true;}

  allFilters.forEach(filter => {
    if (filter !== activeFilter) {
      filter.classList.remove("enabled");
      const tempDisabled = { pointerEvents: "none", opacity: ".5" };
      Object.assign(filter, tempDisabled);
      filter.style.pointerEvents = "none";
      filter.style.opacity = ".5";

      const badges = filter.querySelectorAll(".badge");
      badges.forEach(badge => {
        const filterAll = badge.closest(".filter#all");
        if (filterAll) { Object.assign(badge, { hidden: true, id: "" }); }
        else if (filter && filter.id !== "all") { Object.assign(badge, { hidden: true, textContent: "", id: "" }); }
      });
    } else {
      if (observer) {observer.disconnect();}
      observer = new MutationObserver(() => {
        if (activeFilter.id !== "all") {
          snarkCount = countSnarks();
          activeBadge.textContent = snarkCount;
        }
        activeBadge.hidden = false;
      });
      observer.observe(torrents, { childList: true, subtree: true });
    }

    if (filter !== activeFilter) {
      setTimeout(() => {
        filter.style.pointerEvents = "";
        filter.style.opacity = "";
      }, 2000);
    }
  });

  if (activeFilter) {
    if (!activeFilter.classList.contains("enabled")) {
      activeFilter.classList.add("enabled");
      window.localStorage.setItem("snarkFilter", activeFilter.id);
    }
  }
}

function countSnarks() {
  return torrents?.querySelectorAll("#snarkTbody tr.volatile:not(.peerinfo)").length;
}

function updateURLs() {
  const torrentform = document.getElementById("torrentlist");
  if (!torrentform) {return;}
  var xhrURL = "/i2psnark/.ajax/xhr1.html" + window.location.search;
  const noload = document.getElementById("noload");
  const sortIcon = document.querySelectorAll(".sorter");

  sortIcon.forEach((item) => { item.addEventListener("click", () => { setQuery(); }); });

  function setQuery() {
    const params = window.location.search;
    if (params) {const storage = window.localStorage.setItem("queryString", params);}
  }
}

async function filterNav() {
  const filterbar = document.getElementById("filterBar");
  if (!filterbar) { setTimeout(filterNav, 1500); return; }
  const pagenavtop = document.getElementById("pagenavtop");
  filterbar.addEventListener("click", async function(event) {
    const filterElement = event.target.closest(".filter");
    if (filterElement) {
      event.preventDefault();
      if (!filterElement.classList.contains("enabled")) {filterElement.classList.add("enabled");}
      const filterURL = new URL(filterElement.href);
      const xhrURL = "/i2psnark/.ajax/xhr1.html" + filterURL.search;
      history.replaceState({}, "", filterURL);
      showBadge();
      try {await doRefresh(xhrURL, true);}
      catch {}
      if (pagenavtop) {pagenavtop.hidden = filterElement.id !== "all";}
    }
  });
}

document.addEventListener("DOMContentLoaded", function() { updateURLs(); filterNav(); countSnarks(); showBadge(); });

export {updateURLs, showBadge};