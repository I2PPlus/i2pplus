/* I2P+ netdbLookup.js by dr|z3d */
/* Remove empty query parameters from netdb lookup queries */
/* License: AGPLv3 or later */

/* I2P+ netdbLookup.js by dr|z3d */
/* Remove empty query parameters from netdb lookup queries */
/* License: AGPLv3 or later */

document.addEventListener("DOMContentLoaded", () => {
  const form = document.getElementById("netdbSearchCompact"); // Compact form handler
  if (form) {
    form.querySelector('input[name="nonce"]')?.remove();

    form.addEventListener("submit", event => {
      event.preventDefault();
      const formData = new FormData(event.target);
      const filteredFormData = new FormData();

      if (form.id === "netdbSearchCompact") {
        let field = formData.get("field");
        let query = formData.get("query");
        let nonce = formData.get("nonce");
        if (nonce) filteredFormData.append("nonce", nonce);
        if (field && query) filteredFormData.append(field, query);
      } else {
        for (const [key, value] of formData) {
          const input = form.elements[key];
          if ((input.type === "text" || input.tagName.toLowerCase() === "select" || input.type !== "hidden") && value) {
            filteredFormData.append(key, value);
          }
        }
      }

      const url = new URL(form.action, window.location.href);
      url.search = new URLSearchParams(filteredFormData).toString();
      window.location.href = url.toString();
    });
  }

  // Cleanup URL: remove empty parameters and nonce after page load
  const url = new URL(window.location.href);
  const params = url.searchParams;

  for (const key of Array.from(params.keys())) {
    if (params.get(key) === "" || key === "nonce") {
      params.delete(key);
    }
  }

  // Construct clean URL and update address bar without reloading
  const cleanUrl = url.origin + url.pathname + (params.toString() ? "?" + params.toString() : "");
  window.history.replaceState(null, "", cleanUrl);

  // Populate compact form with existing search parameters if present
  const compactSearch = document.getElementById("netdbSearchCompact");
  if (compactSearch) {
    const urlParams = new URLSearchParams(window.location.search);
    const fieldSelect = compactSearch.querySelector('select[name="field"]');
    const queryInput = compactSearch.querySelector('input[name="query"]');

    // List all possible fields you want to check in order of priority
    const possibleFields = [
      "caps", "cost", "c", "cc", "r", "ip", "ls", "fam",
      "ipv6", "mtu", "port", "ssucaps", "v",
      "type", "etype", "tr",
      "sybil2", "sybil"
    ];

    for (const key of possibleFields) {
      if (urlParams.has(key)) {
        const val = urlParams.get(key);
        if (val) {
          if (fieldSelect) fieldSelect.value = key;
          if (queryInput) queryInput.value = val;
          break; // fill with first found param
        }
      }
    }
  }
});