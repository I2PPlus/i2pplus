/* I2P+ searchLS.js by dr|z3d */
/* Add a search box for leasesets to single page leaseset lookups
/* License: AGPL3 or later */

function searchLS() {
  const container = document.querySelector(".leasesets_container");
  const fragment = document.createDocumentFragment();
  const lsSearchDiv = document.createElement("div");
  container.style.columnCount = "1";
  lsSearchDiv.id = "searchLS";

  const searchInputDiv = document.createElement("div");
  searchInputDiv.id = "searchLsInput";
  searchInputDiv.classList.add("fakeTextInput");
  searchInputDiv.contentEditable = true;
  searchInputDiv.setAttribute("spellcheck", "false");

  const savedQuery = localStorage.getItem("LSquery");
  if (savedQuery) {
    searchInputDiv.textContent = savedQuery;
  }

  const lookupButton = document.createElement("a");
  lookupButton.className = "fakebutton";
  lookupButton.textContent = "Lookup";

  const styleBlock = document.createElement("style");
  styleBlock.innerHTML = `
    #searchLS {
      padding: 8px;
      display: block;
      vertical-align: middle;
      text-align: center;
    }

    #searchLS div[contenteditable] {
      width: 40%;
      max-width: 500px;
      display: inline-block;
      vertical-align: middle;
      outline: none;
    }

    #searchLS a.fakebutton {
      display: inline-block;
      vertical-align: middle;
      text-decoration: none;
      transition: background-color 0.3s ease;
    }
  `;

  searchInputDiv.addEventListener("input", function() {
    const query = searchInputDiv.textContent.trim();
    localStorage.setItem("LSquery", query);
  });

  lookupButton.addEventListener("click", function() {
    const searchInput = document.getElementById("searchLsInput");
    const query = searchInput.textContent.trim();
    if (query !== "") {
      let link = `/netdb?ls=${encodeURIComponent(query)}`;
      localStorage.removeItem("LSquery");
      searchInput.textContent = "";
      window.location.href = link;
    }
  });

  searchInputDiv.addEventListener("keydown", function(event) {
    if (event.key === "Enter") {
      event.preventDefault();
      lookupButton.click();
    }
  });

  lsSearchDiv.appendChild(searchInputDiv);
  lsSearchDiv.appendChild(lookupButton);
  fragment.appendChild(styleBlock);
  fragment.appendChild(lsSearchDiv);
  if (document.getElementById("searchLS") === null) {
    container.appendChild(fragment);
  }

}

export { searchLS };