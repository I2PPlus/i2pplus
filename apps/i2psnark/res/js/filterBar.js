/* I2P+ filterBar.js by dr|z3d */
/* Setup I2PSnark torrent display buttons so we can show/hide snarks
/* based on status and load filtered content via AJAX calls */
/* License: AGPL3 or later */

import {refreshTorrents, doRefresh} from "./refreshTorrents.js";
import {onVisible} from "./onVisible.js";

let filterbar;
let snarkCount;
const torrents = document.getElementById("torrents");

function showBadge() {
  filterbar = document.getElementById("torrentDisplay");
  if (!filterbar) {return;}
  const urlParams = new URLSearchParams(window.location.search), filterQuery = urlParams.get("filter"), searchQuery = urlParams.get("search");
  const filterId = searchQuery !== null ? "search" : (filterQuery || "all");
  const allFilters = filterbar.querySelectorAll(".filter"), activeFilter = document.getElementById(filterId), filterAll = document.getElementById("all");

  allFilters.forEach(filter => {
    const isFilterAll = filter.id === "all";
    const badges = filter.querySelectorAll(".badge");
    if (filter !== activeFilter) {
      filter.classList.remove("enabled");
      if (!isFilterAll) {
        badges.forEach(badge => {
          Object.assign(badge, {hidden: true, textContent: "", id: ""});
        });
      } else {filter.querySelector(".badge").hidden = true;}
    } else if (!isFilterAll) {
      badges.forEach(badge => {
        setTimeout(() => {
          requestAnimationFrame(() => {
            Object.assign(badge, {textContent: countSnarks(), hidden: false, id: "filtercount"});
          });
        }, 1000);
      });
    } else {
      const badge = filterAll.querySelector(".badge");
      Object.assign(badge, {hidden: false, id: "filtercount"});
    }
  });

  if (activeFilter) {
    if (!activeFilter.classList.contains("enabled")) {
      activeFilter.classList.add("enabled");
      const activeBadge = activeFilter.querySelector(".badge");
      activeBadge.id = "filtercount";
      snarkCount = countSnarks();
      if (filterId !== "all") {activeBadge.textContent = snarkCount}
      window.localStorage.setItem("snarkFilter", activeFilter.id);
    }

    const allBadge = filterAll.querySelector("#filtercount.badge");
    if (filterAll.classList.contains("enabled")) {allBadge?.removeAttribute("hidden");}
    else {allBadge?.setAttribute("hidden", "");}
  }
}

function countSnarks() {
  return torrents?.querySelectorAll("#snarkTbody tr.volatile:not(.peerinfo)").length;
}

function updateURLs() {
  var xhrURL = "/i2psnark/.ajax/xhr1.html" + window.location.search;
  const noload = document.getElementById("noload");
  const sortIcon = document.querySelectorAll(".sorter");

  sortIcon.forEach((item) => {
    item.addEventListener("click", () => { setQuery(); });
  });

  function setQuery() {
    const params = window.location.search;
    if (params) {const storage = window.localStorage.setItem("queryString", params);}
  }
}

function checkIfVisible() {
  const torrentform = document.getElementById("torrentlist");
  if (torrentform) { onVisible(torrentform, () => {updateURLs();}); }
}

function filterNav() {
  const filterbar = document.getElementById("torrentDisplay");
  if (!filterbar) {setTimeout(filterNav, 1000); return;}
  const pagenavtop = document.getElementById("pagenavtop");
  filterbar.addEventListener("click", function(event) {
    const filterElement = event.target.closest(".filter");
    if (filterElement) {
      event.preventDefault();
      if (!filterElement.classList.contains("enabled")) {filterElement.classList.add("enabled");}
      const filterURL = new URL(filterElement.href), xhrURL = "/i2psnark/.ajax/xhr1.html" + filterURL.search; history.replaceState({}, "", filterURL);
      showBadge();
      doRefresh(xhrURL, updateURLs);
      if (pagenavtop) { pagenavtop.hidden = filterElement.id !== "all"; }
    }
  });
}

document.addEventListener("DOMContentLoaded", function() {
  checkIfVisible();
  filterNav();
  countSnarks();
  showBadge();
});

export {updateURLs, showBadge};