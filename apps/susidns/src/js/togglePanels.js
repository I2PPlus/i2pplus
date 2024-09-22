/* I2P+ togglePanels.js for SusiDNS by dr|3d */
/* Present buttons to toggle add new host and import hosts panels */
/* License: AGPL3 or later */

(function () {
  document.addEventListener("DOMContentLoaded", function () {
    if (!document.body.classList.contains("dark")) {return;}

    const addToggle = document.getElementById("addNewDest");
    const addContainer = document.getElementById("add");
    const addSubmits = document.querySelectorAll("#addDestForm input[type=submit]");
    const importToggle = document.getElementById("importFromFile");
    const importContainer = document.getElementById("import");
    const importSubmit = document.querySelector("#importHostsForm input[type=submit]");
    const resetAdd = document.querySelector("#add input[type=reset]");
    const resetImport = document.querySelector("#import input[type=reset]");
    const messages = document.querySelector("#messages p");

    [addContainer, importContainer].forEach((container) => {
      container.hidden = true;
    });

    [addToggle, importToggle].forEach((button) => {
      button.removeAttribute("href");
      button.removeAttribute("style");
      button.hidden = false;
    });

    function toggleVisibility(showContainer, hideContainer) {
        showContainer.hidden = false;
        hideContainer.hidden = true;
        toggleBodyClass();
    }

    function toggleBodyClass() {
      const hidden = addContainer.hidden && importContainer.hidden;
      document.body.classList.toggle("displayPanels", !hidden);
    }

    addToggle.addEventListener("click", () =>
      toggleVisibility(addContainer, importContainer)
    );

    importToggle.addEventListener("click", () =>
      toggleVisibility(importContainer, addContainer)
    );

  });
})();
