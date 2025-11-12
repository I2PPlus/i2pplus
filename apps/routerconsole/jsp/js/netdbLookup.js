/* I2P+ netdbLookup.js by dr|z3d */
/* Remove empty query parameters from netdb lookup queries */
/* and implment simple search for /netdb */
/* License: AGPLv3 or later */

document.addEventListener("DOMContentLoaded", () => {
  const form = document.getElementById("netdbSearchCompact");
  if (!form) return;

  form.querySelector('input[name="nonce"]')?.remove();

  // Dropdown options per field
  const dropdownFields = {
    etype: [
      "ELGAMAL_2048",
      "EC_P256",
      "EC_P384",
      "EC_P521",
      "ECIES_X25519",
      "MLKEM512_X25519",
      "MLKEM768_X25519",
      "MLKEM1024_X25519",
      "MLKEM512_X25519_INT",
      "MLKEM768_X25519_INT",
      "MLKEM1024_X25519_INT",
      "MLKEM512_X25519_CT",
      "MLKEM768_X25519_CT",
      "MLKEM1024_X25519_CT"
    ],
    type: [
      "DSA_SHA1",
      "ECDSA_SHA256_P256",
      "ECDSA_SHA384_P384",
      "ECDSA_SHA512_P521",
      "RSA_SHA256_2048",
      "RSA_SHA384_3072",
      "RSA_SHA512_4096",
      "EdDSA_SHA512_Ed25519",
      "EdDSA_SHA512_Ed25519ph",
      "RedDSA_SHA512_Ed25519"
    ],
    tr: [
      "NTCP2",
      "SSU",
      "SSU2"
    ],
    c: [
      ["af", "Afghanistan"],
      ["ax", "Åland Islands"],
      ["al", "Albania"],
      ["dz", "Algeria"],
      ["as", "American Samoa"],
      ["ad", "Andorra"],
      ["ao", "Angola"],
      ["ai", "Anguilla"],
      ["a1", "Anonymous Proxy"],
      ["aq", "Antarctica"],
      ["ag", "Antigua and Barbuda"],
      ["ar", "Argentina"],
      ["am", "Armenia"],
      ["aw", "Aruba"],
      ["ap", "Asia/Pacific Region"],
      ["au", "Australia"],
      ["at", "Austria"],
      ["az", "Azerbaijan"],
      ["bs", "Bahamas"],
      ["bh", "Bahrain"],
      ["bd", "Bangladesh"],
      ["bb", "Barbados"],
      ["by", "Belarus"],
      ["be", "Belgium"],
      ["bz", "Belize"],
      ["bj", "Benin"],
      ["bm", "Bermuda"],
      ["bt", "Bhutan"],
      ["bo", "Bolivia"],
      ["bq", "Bonaire"],
      ["ba", "Bosnia and Herzegovina"],
      ["bw", "Botswana"],
      ["bv", "Bouvet Island"],
      ["br", "Brazil"],
      ["io", "British Indian Ocean Territory"],
      ["bn", "Brunei Darussalam"],
      ["bg", "Bulgaria"],
      ["bf", "Burkina Faso"],
      ["bi", "Burundi"],
      ["kh", "Cambodia"],
      ["cm", "Cameroon"],
      ["ca", "Canada"],
      ["cv", "Cape Verde"],
      ["ky", "Cayman Islands"],
      ["cf", "Central African Republic"],
      ["td", "Chad"],
      ["cl", "Chile"],
      ["cn", "China"],
      ["cx", "Christmas Island"],
      ["cc", "Cocos (Keeling) Islands"],
      ["co", "Colombia"],
      ["km", "Comoros"],
      ["cg", "Congo"],
      ["ck", "Cook Islands"],
      ["cr", "Costa Rica"],
      ["ci", "Cote D'Ivoire"],
      ["hr", "Croatia"],
      ["cu", "Cuba"],
      ["cw", "Curaçao"],
      ["cy", "Cyprus"],
      ["cz", "Czech Republic"],
      ["dk", "Denmark"],
      ["dj", "Djibouti"],
      ["dm", "Dominica"],
      ["do", "Dominican Republic"],
      ["ec", "Ecuador"],
      ["eg", "Egypt"],
      ["sv", "El Salvador"],
      ["gq", "Equatorial Guinea"],
      ["er", "Eritrea"],
      ["ee", "Estonia"],
      ["et", "Ethiopia"],
      ["eu", "European Union"],
      ["fk", "Falkland Islands (Malvinas)"],
      ["fo", "Faroe Islands"],
      ["fj", "Fiji"],
      ["fi", "Finland"],
      ["fr", "France"],
      ["gf", "French Guiana"],
      ["pf", "French Polynesia"],
      ["tf", "French Southern Territories"],
      ["ga", "Gabon"],
      ["gm", "Gambia"],
      ["ge", "Georgia"],
      ["de", "Germany"],
      ["gh", "Ghana"],
      ["gi", "Gibraltar"],
      ["gr", "Greece"],
      ["gl", "Greenland"],
      ["gd", "Grenada"],
      ["gp", "Guadeloupe"],
      ["gu", "Guam"],
      ["gt", "Guatemala"],
      ["gg", "Guernsey"],
      ["gn", "Guinea"],
      ["gw", "Guinea-Bissau"],
      ["gy", "Guyana"],
      ["ht", "Haiti"],
      ["hn", "Honduras"],
      ["hk", "Hong Kong"],
      ["hu", "Hungary"],
      ["is", "Iceland"],
      ["in", "India"],
      ["id", "Indonesia"],
      ["ir", "Iran"],
      ["iq", "Iraq"],
      ["ie", "Ireland"],
      ["im", "Isle of Man"],
      ["il", "Israel"],
      ["it", "Italy"],
      ["jm", "Jamaica"],
      ["jp", "Japan"],
      ["je", "Jersey"],
      ["jo", "Jordan"],
      ["kz", "Kazakhstan"],
      ["ke", "Kenya"],
      ["ki", "Kiribati"],
      ["xk", "Kosovo"],
      ["kw", "Kuwait"],
      ["kg", "Kyrgyzstan"],
      ["la", "Lao People's Democratic Republic"],
      ["lv", "Latvia"],
      ["lb", "Lebanon"],
      ["ls", "Lesotho"],
      ["lr", "Liberia"],
      ["ly", "Libya"],
      ["li", "Liechtenstein"],
      ["lt", "Lithuania"],
      ["lu", "Luxembourg"],
      ["mo", "Macau"],
      ["mk", "Macedonia"],
      ["mg", "Madagascar"],
      ["mw", "Malawi"],
      ["my", "Malaysia"],
      ["mv", "Maldives"],
      ["ml", "Mali"],
      ["mt", "Malta"],
      ["mh", "Marshall Islands"],
      ["mq", "Martinique"],
      ["mr", "Mauritania"],
      ["mu", "Mauritius"],
      ["yt", "Mayotte"],
      ["mx", "Mexico"],
      ["fm", "Micronesia"],
      ["md", "Moldova"],
      ["mc", "Monaco"],
      ["mn", "Mongolia"],
      ["me", "Montenegro"],
      ["ms", "Montserrat"],
      ["ma", "Morocco"],
      ["mz", "Mozambique"],
      ["mm", "Myanmar"],
      ["na", "Namibia"],
      ["nr", "Nauru"],
      ["np", "Nepal"],
      ["nl", "Netherlands"],
      ["an", "Netherlands Antilles"],
      ["nc", "New Caledonia"],
      ["nz", "New Zealand"],
      ["ni", "Nicaragua"],
      ["ne", "Niger"],
      ["ng", "Nigeria"],
      ["nu", "Niue"],
      ["nf", "Norfolk Island"],
      ["mp", "Northern Mariana Islands"],
      ["kp", "North Korea"],
      ["no", "Norway"],
      ["om", "Oman"],
      ["pk", "Pakistan"],
      ["pw", "Palau"],
      ["ps", "Palestinian Territory"],
      ["pa", "Panama"],
      ["pg", "Papua New Guinea"],
      ["py", "Paraguay"],
      ["pe", "Peru"],
      ["ph", "Philippines"],
      ["pn", "Pitcairn Islands"],
      ["pl", "Poland"],
      ["pt", "Portugal"],
      ["pr", "Puerto Rico"],
      ["qa", "Qatar"],
      ["kr", "Republic of Korea"],
      ["re", "Réunion"],
      ["ro", "Romania"],
      ["ru", "Russian Federation"],
      ["rw", "Rwanda"],
      ["bl", "Saint Barthélemy"],
      ["sh", "Saint Helena"],
      ["kn", "Saint Kitts and Nevis"],
      ["lc", "Saint Lucia"],
      ["mf", "Saint Martin"],
      ["pm", "Saint Pierre and Miquelon"],
      ["vc", "Saint Vincent and the Grenadines"],
      ["ws", "Samoa"],
      ["sm", "San Marino"],
      ["st", "Sao Tome and Principe"],
      ["a2", "Satellite Provider"],
      ["sa", "Saudi Arabia"],
      ["sn", "Senegal"],
      ["rs", "Serbia"],
      ["cs", "Serbia and Montenegro"],
      ["sc", "Seychelles"],
      ["sl", "Sierra Leone"],
      ["sg", "Singapore"],
      ["sx", "Sint Maarten"],
      ["sk", "Slovakia"],
      ["si", "Slovenia"],
      ["sb", "Solomon Islands"],
      ["so", "Somalia"],
      ["za", "South Africa"],
      ["gs", "South Georgia and the South Sandwich Islands"],
      ["ss", "South Sudan"],
      ["es", "Spain"],
      ["lk", "Sri Lanka"],
      ["sd", "Sudan"],
      ["sr", "Suriname"],
      ["sj", "Svalbard and Jan Mayen"],
      ["sz", "Swaziland"],
      ["se", "Sweden"],
      ["ch", "Switzerland"],
      ["sy", "Syria"],
      ["tw", "Taiwan"],
      ["tj", "Tajikistan"],
      ["tz", "Tanzania"],
      ["th", "Thailand"],
      ["tl", "Timor-Leste"],
      ["tg", "Togo"],
      ["tk", "Tokelau"],
      ["to", "Tonga"],
      ["tt", "Trinidad and Tobago"],
      ["tn", "Tunisia"],
      ["tr", "Turkey"],
      ["tm", "Turkmenistan"],
      ["tc", "Turks and Caicos Islands"],
      ["tv", "Tuvalu"],
      ["ug", "Uganda"],
      ["ua", "Ukraine"],
      ["ae", "United Arab Emirates"],
      ["gb", "United Kingdom"],
      ["us", "United States"],
      ["um", "United States Minor Outlying Islands"],
      ["zz", "Unknown"],
      ["uy", "Uruguay"],
      ["uz", "Uzbekistan"],
      ["vu", "Vanuatu"],
      ["va", "Vatican"],
      ["ve", "Venezuela"],
      ["vn", "Vietnam"],
      ["vi", "Virgin Islands"],
      ["wf", "Wallis and Futuna"],
      ["eh", "Western Sahara"],
      ["ye", "Yemen"],
      ["zm", "Zambia"],
      ["zw", "Zimbabwe"]
    ]
  };

  const fieldSelect = form.querySelector('select[name="field"]');
  const queryCell = form.querySelector('input[name="query"]')?.closest('td');
  if (!fieldSelect || !queryCell) return;

  if (fieldSelect) {
    const options = Array.from(fieldSelect.options);
    options.sort((a, b) => {
      return a.text.localeCompare(b.text);
    });
    fieldSelect.innerHTML = "";
    options.forEach(opt => fieldSelect.appendChild(opt));
  }

  let currentQueryElement = form.querySelector('input[name="query"]');

  function createSelectForField(field, value = "") {
    const select = document.createElement("select");
    select.name = "query";
    const labels = { etype: "encryption", type: "signature", tr: "transport", c: "country" };
    select.title = `Select ${labels[field] || field}`;

    const options = dropdownFields[field] || [];

    options.forEach(opt => {
      const option = document.createElement("option");
      if (Array.isArray(opt)) {
        option.value = opt[0];
        option.textContent = `${opt[1]} (${opt[0].toUpperCase()})`;
      } else {
        option.value = opt;
        option.textContent = opt;
      }
      if (option.value === value) option.selected = true;
      select.appendChild(option);
    });

    return select;
  }

  function createTextInput(value = "") {
    const input = document.createElement("input");
    input.type = "text";
    input.name = "query";
    input.title = "Enter search value here";
    input.value = value;
    return input;
  }

  // 🔥 Clear query when switching fields (unless same field)
  let lastField = fieldSelect.value;
  function switchQueryField() {
    const newField = fieldSelect.value;
    // Only clear if field actually changed
    const shouldClear = newField !== lastField;
    lastField = newField;

    if (currentQueryElement) {
      currentQueryElement.remove();
    }

    if (newField in dropdownFields) {
      // For dropdowns, only preserve value if it's valid AND field didn't change
      const preservedValue = shouldClear ? "" : currentQueryElement?.value || "";
      currentQueryElement = createSelectForField(newField, preservedValue);
    } else {
      // Always clear text input on field change
      currentQueryElement = createTextInput(shouldClear ? "" : currentQueryElement?.value || "");
    }

    queryCell.appendChild(currentQueryElement);
  }

  fieldSelect.addEventListener("change", switchQueryField);

  // Form submission
  form.addEventListener("submit", event => {
    event.preventDefault();
    const field = fieldSelect.value;
    const query = currentQueryElement ? currentQueryElement.value.trim() : "";

    const url = new URL(form.action, window.location.href);
    url.search = "";
    if (field && query) {
      url.searchParams.set(field, query);
    }
    url.searchParams.delete("nonce");
    window.location.href = url.toString();
  });

  // Clean URL
  const url = new URL(window.location.href);
  for (const key of Array.from(url.searchParams.keys())) {
    if (url.searchParams.get(key) === "" || key === "nonce") {
      url.searchParams.delete(key);
    }
  }
  const cleanUrl = url.origin + url.pathname + (url.searchParams.toString() ? "?" + url.searchParams.toString() : "");
  window.history.replaceState(null, "", cleanUrl);

  // Populate from URL — must run AFTER initial DOM setup
  const urlParams = new URLSearchParams(window.location.search);
  const possibleFields = [
    "caps", "cost", "c", "cc", "r", "ip", "ls", "fam",
    "ipv6", "mtu", "port", "ssucaps", "v",
    "type", "etype", "tr",
    "sybil2", "sybil"
  ];

  let found = false;
  for (const key of possibleFields) {
    if (urlParams.has(key)) {
      const val = urlParams.get(key);
      if (val) {
        fieldSelect.value = key;
        lastField = key; // sync lastField to avoid premature clear
        setTimeout(() => {
          const isDropdown = key in dropdownFields;
          if (isDropdown) {
            switchQueryField(); // this will use preservedValue = "" but we override below
            if (currentQueryElement?.tagName === "SELECT") {
              const valid = Array.from(currentQueryElement.options).some(opt => opt.value === val);
              if (valid) {
                currentQueryElement.value = val;
              }
              // If invalid, leave as first option (or blank if empty)
            }
          } else {
            switchQueryField(); // clears if field changed, but we just loaded
            if (currentQueryElement?.tagName === "INPUT") {
              currentQueryElement.value = val;
            }
          }
        }, 0);
        found = true;
        break;
      }
    }
  }

  // If no param, ensure initial state is clean
  if (!found) {
    // Keep initial input, but ensure lastField is synced
    lastField = fieldSelect.value;
  }
});