/* I2PSnark toggleConfigs.js by dr|3d */
/* Enable toggling of I2PSnark's trackers and filter configuration panels */
/* License: AGPL3 or later */

const filterConfigTable = document.querySelector("#fileFilter table");
const trackerConfigTable = document.querySelector("#trackers table");
const isIframed = document.documentElement.classList.contains("iframed") || window.self != window.top;

function toggleConfig(table, title) {
  if (table.style.display === "none" || table.style.display === "") {
    table.style.display = "table";
    title.classList.add("expanded");

    if (isIframed) {
      parent.postMessage({ command: "scrollToElement", id: title.id }, location.origin);
      parent.postMessage({ action: 'resize', iframeId: 'i2psnarkframe' }, location.origin);
    } else {scrollToElement(title);}
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
  configTitles.forEach((title) => title.classList.remove("expanded"));

  const setupClickListener = async (elementId, configTable) => {
    const button = document.getElementById(elementId);
    button.addEventListener("click", async (e) => {
      const clickedTitle = e.target.closest(".configTitle");
      if (clickedTitle) {
        await toggleConfig(configTable, clickedTitle);
        setTimeout(() => {
          requestAnimationFrame(() => {
            clickedTitle.scrollIntoView({block: "center", inline: "center", behavior: "smooth"});
          });
        }, 60);
      }
    });
  };

  setupClickListener("fileFilter", filterConfigTable);
  setupClickListener("trackers", trackerConfigTable);
});