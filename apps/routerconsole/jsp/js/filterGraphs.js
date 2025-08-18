/* I2P+ filterGraphs.js by dr|z3d */
/* Filter graphs based on alt tag graph name */
/* License: AGPLv3 or later */

document.addEventListener("DOMContentLoaded", function () {
  if (theme !== "dark") {return;}
  const searchContainer = document.createElement("div"),
    searchInput = document.createElement("input"),
    clearFilterButton = document.createElement("button");

  searchContainer.id = "graphFilter";
  searchInput.type = "text";
  searchInput.id = "searchInput";
  clearFilterButton.textContent = "Clear Filter";

  clearFilterButton.addEventListener("click", () => {
    searchInput.value = "";
    searchInput.dispatchEvent(new Event("input"));
    localStorage.removeItem("graphFilterText");
  });

  searchContainer.appendChild(searchInput);
  searchContainer.appendChild(clearFilterButton);

  const container = document.querySelector("h1.perf");
  if (container) container.insertBefore(searchContainer, container.firstChild);

  function applyFilter() {
    const filterText = searchInput.value.trim().toLowerCase(),
      graphContainers = document.querySelectorAll(".graphContainer");

    graphContainers.forEach((container) => {
      const img = container.querySelector("img");
      if (!img) return (container.style.display = "none");

      const checkDisplay = () => {
        const altText = img.alt.toLowerCase();
        container.style.display = filterText === "" || altText.includes(filterText) ? "" : "none";
      };

      if (!img.complete) {
        img.onload = img.onerror = checkDisplay;
        return;
      }

      checkDisplay();
    });
  }

  const savedFilter = localStorage.getItem("graphFilterText") || "";
  searchInput.value = savedFilter;

  searchInput.addEventListener("input", () => {
    const filterText = searchInput.value.trim();
    localStorage.setItem("graphFilterText", filterText);
    applyFilter();
  });

  applyFilter();
  setTimeout(applyFilter, 500);
  setTimeout(applyFilter, 1000);
  setTimeout(applyFilter, 2000);

  const targetNode = document.getElementById("allgraphs");
  if (targetNode) {
    const observer = new MutationObserver(() => setTimeout(applyFilter, 100));
    observer.observe(targetNode, { childList: true, subtree: true });
  }
});