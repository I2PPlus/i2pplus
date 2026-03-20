/**
 * @module refreshStatus
 * @file I2P+ SusiDNS status refresher.
 * Automatically refreshes host status elements in the address book at
 * regular intervals using AJAX polling.
 * @author dr|z3d
 * @license AGPL3 or later
 */

import { refreshElements } from "/js/refreshElements.js";

/**
 * On DOM load, locates the host list table and begins polling
 * status cells at a 10-second interval.
 * @listens DOMContentLoaded
 */
document.addEventListener("DOMContentLoaded", () => {
  /** @type {HTMLElement|null} */
  const hosts = document.getElementById("host_list");
  if (!hosts) {return;}
  /** @type {string} */
  const url = window.location.href;
  /** @type {NodeListOf<HTMLTableCellElement>} */
  const status = hosts.querySelectorAll("td.status");
  refreshElements(status, url, 10000);
});