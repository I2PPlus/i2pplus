/* I2P+ I2PSnark setFilterQuery.js by dr|z3d */
/* Assign filter parameter to main snark navigation and cancel search buttons */
/* License: AGPL3 or later */

const navbar = document.getElementById("navbar");
const navMain = navbar.querySelector(".nav_main");
const cancelSearch = navbar.querySelector("#searchwrap a");

function setFilterQuery() {
  if (!navbar || !navMain || !cancelSearch) return;

  function updateHref(element) {
    const filter = new URLSearchParams(window.location.search).get("filter") || localStorage.getItem("snarkFilter");
    if (filter && filter !== "all" && filter !== "search" && filter !== "") {
      element.href = `/i2psnark/?filter=${filter}`;
    }
  }

  navbar.addEventListener("click", (event) => {
    if (event.target.classList.contains("nav_main")) {updateHref(navMain);}
  });
  cancelSearch.addEventListener("click", (event) => {
    if (event.target.href.includes("i2psnark")) {updateHref(cancelSearch);}
  });
}

document.addEventListener("DOMContentLoaded", setFilterQuery);