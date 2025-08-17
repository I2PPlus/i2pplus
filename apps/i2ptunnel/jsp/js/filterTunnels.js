/* I2P+ filterTunnels.js by dr|z3d */
/* Realtime filtering of tunnel manager tunnels based on input text
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", function () {
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
    const globalH2 = document.querySelector("#globalTunnelControl h2");
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