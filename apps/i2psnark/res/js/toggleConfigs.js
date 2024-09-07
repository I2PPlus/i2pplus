/* I2PSnark toggleConfigs.js by dr|3d */
/* Enable toggling of I2PSnark's trackers and filter configuration panels */
/* License: AGPL3 or later */

const filterConfigTable = document.querySelector("#fileFilter table");
const trackerConfigTable = document.querySelector("#trackers table");

function toggleConfig(table, title) {
  if (table.style.display === "none" || table.style.display === "") {
    table.style.display = "table";
    title.classList.add("expanded");
  } else {
    table.style.display = "none";
    title.classList.remove("expanded");
  }
}

document.addEventListener("DOMContentLoaded", () => {
  const configTitles = document.querySelectorAll("#fileFilter .configTitle, #trackers .configTitle");

  document.getElementById("fileFilter").addEventListener("click", (e) => {
    if (e.target.classList.contains("configTitle")) {
      toggleConfig(filterConfigTable, e.target);
    }
  });

  document.getElementById("trackers").addEventListener("click", (e) => {
    if (e.target.classList.contains("configTitle")) {
      toggleConfig(trackerConfigTable, e.target);
    }
  });
});