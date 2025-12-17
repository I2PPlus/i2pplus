import { refreshElements } from "/js/refreshElements.js";

document.addEventListener("DOMContentLoaded", () => {
  const hosts = document.getElementById("host_list");
  if (!hosts) {return;}
  const url = window.location.href;
  const status = hosts.querySelectorAll("td.status");
  refreshElements(status, url, 10000);
});