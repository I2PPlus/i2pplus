/**
 * @module clearSearch
 * @file I2P+ SusiDNS clear-search widget.
 * Injects a clear-search button alongside the search input field and
 * dynamically shows/hides it based on input content.
 * @author dr|z3d
 * @license AGPL3 or later
 */

document.addEventListener("DOMContentLoaded", function () {
  /** @type {HTMLInputElement|null} */
  const searchInput = document.querySelector("#searchInput input[type=text]");
  /** @type {HTMLAnchorElement|null} */
  const clearSearch = document.getElementById("clearSearch");
  if (!clearSearch) {return;}

  /**
   * Updates the clear-search anchor visibility and href based on whether
   * the search input currently contains text.
   *
   * @function handleClear
   * @returns {void}
   */
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