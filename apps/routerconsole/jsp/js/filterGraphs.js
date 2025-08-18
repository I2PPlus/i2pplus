/* I2P+ filterGraphs.js by dr|z3d */
/* Filter graphs based on alt tag graph name */
/* License: AGPLv3 or later */

document.addEventListener("DOMContentLoaded", function () {
  if (theme !== "dark") return;

  const searchContainer = document.createElement("div"),
    searchInput = document.createElement("input"),
    clearFilterButton = document.createElement("button");

  searchContainer.id = "graphFilter";
  searchInput.type = "text";
  searchInput.id = "searchInput";
  searchInput.placeholder = "Search by alt text...";
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
      aliasMap = { transit: "participating" },
      resolvedFilter = aliasMap[filterText] || filterText,
      graphContainers = document.querySelectorAll(".graphContainer");

    graphContainers.forEach((container) => {
      const img = container.querySelector("img");
      if (!img) return void (container.style.display = "none");

      const checkDisplay = () => {
        const altText = (img.alt || "").toLowerCase();
        container.style.display = filterText === "" || altText.includes(resolvedFilter) ? "" : "none";
      };

      if (!img.complete) {
        img.onload = () => {
          checkDisplay();
          img.onerror = null;
        };
        img.onerror = () => {
          container.style.display = "none";
        };
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

  let filterTimeout;
  function debounceFilter(delay = 200) {
    clearTimeout(filterTimeout);
    filterTimeout = setTimeout(applyFilter, delay);
  }

  applyFilter();
  debounceFilter(100);

  const targetNode = document.getElementById("allgraphs");
  if (targetNode) {
    const observer = new MutationObserver(debounceFilter);
    observer.observe(targetNode, { childList: true, subtree: true });
  }

  const imgObserver = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          debounceFilter(100);
          imgObserver.unobserve(entry.target);
        }
      });
    },
    { rootMargin: "0px 0px 200px 0px" }
  );

  document.querySelectorAll(".graphContainer img").forEach((img) => {
    imgObserver.observe(img);
  });
});