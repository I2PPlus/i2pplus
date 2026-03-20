/**
 * @module filterTunnels
 * @file filterTunnels.js - Realtime filtering of I2PTunnel Manager tunnels
 * @description Creates a search input that filters tunnel blocks based on user input
 * matching against tunnel name, type, interface, location, and port
 * @author dr|z3d
 * @license AGPL3 or later
 */

/**
 * Initializes the search filter UI on DOM ready.
 * Creates a search input and clear button, appends them to the global tunnel control header,
 * and sets up input event listener for realtime filtering.
 * @listens DOMContentLoaded
 * @returns {void}
 */
document.addEventListener("DOMContentLoaded", function () {
    const globalH2 = document.querySelector("#globalTunnelControl h2");
    if (!globalH2) {return;}
    const searchContainer = document.createElement("div"),
          searchInput = document.createElement("input"),
          clearFilterButton = document.createElement("button");
    searchContainer.id = "searchFilter";
    searchInput.type = "text";
    searchInput.id = "searchInput";
    searchInput.placeholder = "Search...";
    clearFilterButton.textContent = "Clear Filter";
    clearFilterButton.addEventListener("click", function () {
        searchInput.value = "";
        searchInput.dispatchEvent(new Event("input"));
    });
    searchContainer.appendChild(searchInput);
    searchContainer.appendChild(clearFilterButton);
    globalH2.insertBefore(searchContainer, globalH2.firstChild);
    searchInput.addEventListener("input", function () {
        const filterText = searchInput.value.trim().toLowerCase(),
              tunnelBlocks = document.querySelectorAll(".tunnelBlock");
        tunnelBlocks.forEach((tunnelBlock) => {
            let visible = false;
            ["tunnelName", "tunnelType", "tunnelInterface", "tunnelLocation", "tunnelPort"].forEach((selector) => {
                const element = tunnelBlock.querySelector(`.${selector}`);
                if (element && element.textContent.toLowerCase().includes(filterText)) {
                    visible = true;
                }
            });
            tunnelBlock.style.display = visible ? "" : "none";
        });
    });
});