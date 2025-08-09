/* I2P+ SusiDNS clearSearch.js by dr|z3d */
/* Add clear search widget to search input */
/* License: AGPL3 or later */

document.addEventListener("DOMContentLoaded", function () {
  const searchInput = document.querySelector("#searchInput input[type=text]");
  const clearSearch = document.getElementById("clearSearch");
  if (!clearSearch) {return;}

  function handleClear() {
    const inputValue = searchInput.value.trim();
    const currentURL = new URL(window.location.href);
    const activeBook = currentURL.searchParams.get("book") || "router";
    const isIframed = document.documentElement.classList.contains("iframed");
    const noFilterURL = `addressbook?book=${activeBook}&filter=none`;

    clearSearch.href = inputValue ? noFilterURL : "";
    clearSearch.style.display = inputValue ? "inline" : "none";
  }

  searchInput.addEventListener("input", handleClear);
  handleClear();

  const styles = document.createElement("style");
  styles.innerHTML =
    '#clearSearch{width:12px;height:12px;position:absolute;top:3px;right:56px;background:var(--no) no-repeat right center/12px;animation:fade-in .6s ease 1s both}' +
    '#clearSearch:active{transform:scale(0.8)}' +
    '#searchInput{position:relative}' +
    '#booksearch input[name="search"],#booksearch input[name="search"]:focus{padding-right:30px}';
  document.head.appendChild(styles);
});