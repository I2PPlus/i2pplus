/* I2PSnark toggleConfigs.js by dr|3d */
/* Enable toggling of I2PSnark's trackers and filter configuration panels */
/* License: AGPL3 or later */

const filterConfigTable = document.querySelector("#fileFilter table");
const trackerConfigTable = document.querySelector("#trackers table");

function toggleConfig(table, title) {
  if (table.style.display === "none" || table.style.display === "") {
    table.style.display = "table";
    title.classList.add("expanded");

    // Check if the script is running inside an iframe
    if (window !== parent.window) {
      parent.postMessage({ command: "scrollToElement", id: title.id }, "*");
    } else {
      scrollToElement(title);
    }
  } else {
    table.style.display = "none";
    title.classList.remove("expanded");
  }
}

function scrollToElement(element, windowObj = window) {
  const elementPosition = element.getBoundingClientRect().top;
  const scrollPosition = elementPosition + (windowObj !== window ? windowObj.pageYOffset : window.pageYOffset);
  windowObj.scrollTo({ top: scrollPosition, behavior: "smooth" });
}

document.addEventListener("DOMContentLoaded", () => {
  const configTitles = document.querySelectorAll(".configTitle");
  configTitles.forEach((title) => {
    title.classList.remove("expanded");
  });

  document.getElementById("fileFilter").addEventListener("click", (e) => {
    const clickedTitle = e.target.closest(".configTitle");
    if (clickedTitle) {
      toggleConfig(filterConfigTable, clickedTitle);
    }
  });

  document.getElementById("trackers").addEventListener("click", (e) => {
    const clickedTitle = e.target.closest(".configTitle");
    if (clickedTitle) {
      toggleConfig(trackerConfigTable, clickedTitle);
    }
  });
});
