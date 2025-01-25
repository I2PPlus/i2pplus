/* I2P+ togglePanels.js for SusiDNS by dr|3d */
/* Present buttons to toggle add new host and import hosts panels */
/* License: AGPL3 or later */

(function () {
  document.addEventListener("DOMContentLoaded", function () {
    const darkTheme = document.body.classList.contains("dark");
    const lightTheme = document.body.classList.contains("light");
    if (!darkTheme && !lightTheme) {return;}

    const addContainer = document.getElementById("add");
    const addReset = document.querySelector("#add input[type=reset]");
    const addSubmits = document.querySelectorAll("#addDestForm input[type=submit]");
    const addTextInputs = document.querySelectorAll("#add input[type=text]");
    const addToggle = document.getElementById("addNewDest");
    const importContainer = document.getElementById("import");
    const importReset = document.querySelector("#import input[type=reset]");
    const importSubmit = document.querySelector("#importHostsForm input[type=submit]");
    const importToggle = document.getElementById("importFromFile");
    const messages = document.querySelector("#messages p");
    const isIframed = document.documentElement.classList.contains("iframed") || window.top !== window.parent.top;

    [addContainer, importContainer].forEach((container) => {
      if (container) {container.hidden = true;}
    });

    addTextInputs.forEach(input => {input.value = "";});

    [addToggle, importToggle].forEach((button) => {
      if (button) {
        button.removeAttribute("href");
        button.removeAttribute("style");
        button.hidden = false;
      }
    });

    function toggleVisibility(showContainer, hideContainer) {
      if (showContainer) {showContainer.hidden = false;}
      if (hideContainer) {hideContainer.hidden = true;}
      toggleBodyClass();
      if (!showContainer.hidden) {
        document.documentElement.classList.add("noscroll");
        const page = document.getElementById("page");
        if (lightTheme) {page.style.minHeight = "230px";}
        else {page.style.minHeight = "255px";}
        if (isIframed) {
          window.parent.document.documentElement.style.overflow = "hidden";
        }
      }
    }

    function toggleBodyClass() {
      const shouldHide = addContainer?.classList.contains("isHidden") || importContainer?.classList.contains("isHidden");
      const hidden = addContainer.hidden && importContainer.hidden || shouldHide;
      if (!shouldHide) {document.body.classList.toggle("displayPanels", !hidden);}
      else {
        setTimeout(() => {
          document.body.classList.remove("displayPanels");
          document.documentElement.classList.remove("noscroll");
          page.style.minHeight = null;
          if (isIframed) {window.parent.document.documentElement.style.overflow = null;}
        }, 240);
      }
    }

    function resetAddInputs() {
      addTextInputs.forEach(input => {
        input.removeAttribute("value");
        input.setAttribute("value", "");
      });
      addContainer.classList.add("isHidden");
      setTimeout(() => {
        toggleBodyClass();
        addContainer.hidden = true;
        addContainer.classList.remove("isHidden");
      }, 240);
    }

    addToggle?.addEventListener("click", () =>
      toggleVisibility(addContainer, importContainer)
    );

    importToggle?.addEventListener("click", () =>
      toggleVisibility(importContainer, addContainer)
    );

    addReset?.addEventListener("click", resetAddInputs);

    importReset?.addEventListener("click", () => {
      importContainer.classList.add("isHidden");
      toggleBodyClass();
      setTimeout(() => {
        importContainer.hidden = true;
        importContainer.classList.remove("isHidden");
      }, 240);
    });

  });
})();