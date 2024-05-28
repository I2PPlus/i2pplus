/* I2P+ filterBar.js by dr|z3d */
/* Setup I2PSnark torrent display buttons so we can show/hide snarks
/* based on status and load filtered content via AJAX calls */
/* License: AGPL3 or later */

import {refreshTorrents, xhrsnark, doRefresh, getURL} from "./refreshTorrents.js";
import {onVisible} from "./onVisible.js";

const filterbar = document.getElementById("torrentDisplay");
let snarkCount;

function showBadge() {
  if (!filterbar) {return;}
  const query = new URLSearchParams(window.location.search);
  const filterQuery = query.get("filter");
  const allFilters = filterbar.querySelectorAll(".filter");
  const activeFilter = document.querySelector(".filter[id='" + (filterQuery != null ? filterQuery : "all") + "']");
  allFilters.forEach(filter => {
    const badges = filter.querySelectorAll(".filter:not(.enabled):not(#all) .badge");
    if (filter !== activeFilter) {
      filter.classList.remove("enabled");
      badges.forEach(badge => badge.innerText = "");
      badges.forEach(badge => badge.hidden = true);
    } else {badges.forEach(badge => badge.hidden = "");}
  });
  if (activeFilter) {
    if (!activeFilter.classList.contains("enabled")) {
      activeFilter.classList.add("enabled");
      window.localStorage.setItem("snarkFilter", activeFilter.id);
    }
    snarkCount = xhrsnark.responseXML?.querySelectorAll("#snarkTbody tr.volatile:not(.peerinfo)").length;
    const activeBadge = activeFilter.querySelector(".badge");
    const filterAll = torrentDisplay.querySelector(".filter#all");
    activeBadge.id = "filtercount";
    if (!filterAll.classList.contains("enabled")) {
      filterAll.querySelector(".badge").setAttribute("hidden", "");
      activeBadge.textContent = snarkCount;
    } else {
      filterAll.querySelector(".badge").removeAttribute("hidden");
    }
  }
}

function updateURLs() {
  var xhrURL = "/i2psnark/.ajax/xhr1.html" + window.location.search;
  const noload = document.getElementById("noload");
  const sortIcon = document.querySelectorAll(".sorter");

  sortIcon.forEach((item) => {
    item.addEventListener("click", () => {
      setQuery();
    });
  });

  function setQuery() {
    const params = window.location.search;
    if (params) {const storage = window.localStorage.setItem("queryString", params);}
  }

}

function checkIfVisible() {
  const torrentform = document.getElementById("torrentlist");
  if (torrentform !== null) {
    onVisible(torrentform, () => {
      updateURLs();
    });
  }
}

function filterNav() {
  if (!filterbar) {return;}
  const torrents = document.getElementById("torrents");
  const torrentForm = document.getElementById("torrentlist");
  const filterbar = document.getElementById("torrentDisplay");
  const pagenavtop = document.getElementById("pagenavtop");
  let filterURL;
  let xhrURL;
  filterbar.addEventListener("click", function(event) {
    if (event.target.closest(".filter")) {
      event.preventDefault();
      const clickedElement = event.target;
      filterURL = new URL(event.target.closest(".filter").href);
      xhrURL = "/i2psnark/.ajax/xhr1.html" + filterURL.search;
      history.replaceState({}, "", filterURL);
      doRefresh(xhrURL, updateURLs);
      if (pagenavtop) {
        if (event.target.closest(".filter").id === "all") {
          pagenavtop.classList.remove("hidden");
          pagenavtop.removeAttribute("hidden");
        } else if (!pagenavtop.hasAttribute("hidden")) {
          pagenavtop.setAttribute("hidden", "");
          pagenavtop.classList.add("hidden");
        }
      }
    }
  });
}

document.addEventListener("DOMContentLoaded", function() {
  checkIfVisible();
  filterNav();
});

export {updateURLs, showBadge};