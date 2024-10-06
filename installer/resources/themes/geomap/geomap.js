/* I2P+ geomap.js by dr|z3d */
/* Heavily modified from https://cartosvg.com/mercator */

(function () {

  const geomap = document.querySelector("#geomap");

  // Initialize DOMParser for potential future parsing needs
  const parser = new DOMParser();

  const STORE_INTERVAL = 30000;
  const DEBOUNCE_DELAY = 60;
  const TIMEOUT = 15000;

  // Define constants for dimensions
  const width = 720;
  const height = 475;

  // Calculate the inverse of the screen coordinate transformation matrix for geomap
  const screenCTMInverse = geomap.getScreenCTM().inverse();

  // Prepare the tooltip object and element
  const tooltipInfo = { shapeId: null, element: null };
  tooltipInfo.element = createTooltip({}, "", 1, 1);
  geomap.appendChild(tooltipInfo.element);
  tooltipInfo.element.style.display = "none";

  const m = {
    data: {
      countries: {
        Afghanistan: { region: "Asia", code: "af" },
        Albania: { region: "Europe", code: "al" },
        Algeria: { region: "Africa", code: "dz" },
        Andorra: { region: "Europe", code: "ad" },
        Angola: { region: "Africa", code: "ao" },
        "Antigua and Barbuda": { region: "Americas", code: "ag" },
        Argentina: { region: "Americas", code: "ar" },
        Armenia: { region: "Asia", code: "am" },
        Australia: { region: "Oceania", code: "au" },
        Austria: { region: "Europe", code: "at" },
        Azerbaijan: { region: "Asia", code: "az" },
        Bahamas: { region: "Americas", code: "bs" },
        Bahrain: { region: "Asia", code: "bh" },
        Bangladesh: { region: "Asia", code: "bd" },
        Barbados: { region: "Americas", code: "bb" },
        Belarus: { region: "Europe", code: "by" },
        Belgium: { region: "Europe", code: "be" },
        Belize: { region: "Americas", code: "bz" },
        Benin: { region: "Africa", code: "bj" },
        Bhutan: { region: "Asia", code: "bt" },
        Bolivia: { region: "Americas", code: "bo" },
        "Bosnia and Herzegovina": { region: "Europe", code: "ba" },
        Botswana: { region: "Africa", code: "bw" },
        Brazil: { region: "Americas", code: "br" },
        "Brunei Darussalam": { region: "Asia", code: "bn" },
        Bulgaria: { region: "Europe", code: "bg" },
        "Burkina Faso": { region: "Africa", code: "bf" },
        Burundi: { region: "Africa", code: "bi" },
        "Cabo Verde": { region: "Africa", code: "cv" },
        Cambodia: { region: "Asia", code: "kh" },
        Cameroon: { region: "Africa", code: "cm" },
        Canada: { region: "Americas", code: "ca" },
        "Central African Republic": { region: "Africa", code: "cf" },
        Chad: { region: "Africa", code: "td" },
        Chile: { region: "Americas", code: "cl" },
        China: { region: "Asia", code: "cn" },
        Colombia: { region: "Americas", code: "co" },
        Comoros: { region: "Africa", code: "km" },
        "Congo (Democratic Republic)": { region: "Africa", code: "cd" },
        Congo: { region: "Africa", code: "cg" },
        "Costa Rica": { region: "Americas", code: "cr" },
        "Côte d'Ivoire": { region: "Africa", code: "ci" },
        Croatia: { region: "Europe", code: "hr" },
        Cuba: { region: "Americas", code: "cu" },
        Cyprus: { region: "Asia", code: "cy" },
        Czechia: { region: "Europe", code: "cz" },
        Denmark: { region: "Europe", code: "dk" },
        Djibouti: { region: "Africa", code: "dj" },
        "Dominican Republic": { region: "Americas", code: "do" },
        Dominica: { region: "Americas", code: "dm" },
        Ecuador: { region: "Americas", code: "ec" },
        Egypt: { region: "Africa", code: "eg" },
        "El Salvador": { region: "Americas", code: "sv" },
        "Equatorial Guinea": { region: "Africa", code: "gq" },
        Eritrea: { region: "Africa", code: "er" },
        Estonia: { region: "Europe", code: "ee" },
        Eswatini: { region: "Africa", code: "sz" },
        Ethiopia: { region: "Africa", code: "et" },
        "Falkland Islands": { region: "Americas", code: "fk" },
        Fiji: { region: "Oceania", code: "fj" },
        Finland: { region: "Europe", code: "fi" },
        France: { region: "Europe", code: "fr" },
        Gabon: { region: "Africa", code: "ga" },
        Gambia: { region: "Africa", code: "gm" },
        Georgia: { region: "Asia", code: "ge" },
        Germany: { region: "Europe", code: "de" },
        Ghana: { region: "Africa", code: "gh" },
        Greece: { region: "Europe", code: "gr" },
        Greenland: { region: "Americas", code: "gl" },
        Grenada: { region: "Americas", code: "gd" },
        Guatemala: { region: "Americas", code: "gt" },
        "Guinea-Bissau": { region: "Africa", code: "gw" },
        Guinea: { region: "Africa", code: "gn" },
        Guyana: { region: "Americas", code: "gy" },
        Haiti: { region: "Americas", code: "ht" },
        Honduras: { region: "Americas", code: "hn" },
        Hungary: { region: "Europe", code: "hu" },
        Iceland: { region: "Europe", code: "is" },
        India: { region: "Asia", code: "in" },
        Indonesia: { region: "Asia", code: "id" },
        Iran: { region: "Asia", code: "ir" },
        Iraq: { region: "Asia", code: "iq" },
        Ireland: { region: "Europe", code: "ie" },
        Israel: { region: "Asia", code: "il" },
        Italy: { region: "Europe", code: "it" },
        Jamaica: { region: "Americas", code: "jm" },
        Japan: { region: "Asia", code: "jp" },
        Jordan: { region: "Asia", code: "jo" },
        Kazakhstan: { region: "Asia", code: "kz" },
        Kenya: { region: "Africa", code: "ke" },
        Kiribati: { region: "Oceania", code: "ki" },
        "North Korea": { region: "Asia", code: "kp" },
        "South Korea": { region: "Asia", code: "kr" },
        Kuwait: { region: "Asia", code: "kw" },
        Kyrgyzstan: { region: "Asia", code: "kg" },
        "Lao People's Democratic Republic": { region: "Asia", code: "la" },
        Latvia: { region: "Europe", code: "lv" },
        Lebanon: { region: "Asia", code: "lb" },
        Lesotho: { region: "Africa", code: "ls" },
        Liberia: { region: "Africa", code: "lr" },
        Libya: { region: "Africa", code: "ly" },
        Liechtenstein: { region: "Europe", code: "li" },
        Lithuania: { region: "Europe", code: "lt" },
        Luxembourg: { region: "Europe", code: "lu" },
        Madagascar: { region: "Africa", code: "mg" },
        Malawi: { region: "Africa", code: "mw" },
        Malaysia: { region: "Asia", code: "my" },
        Mali: { region: "Africa", code: "ml" },
        Malta: { region: "Europe", code: "mt" },
        Mauritania: { region: "Africa", code: "mr" },
        Mauritius: { region: "Africa", code: "mu" },
        Mexico: { region: "Americas", code: "mx" },
        "Micronesia (Federated States of)": { region: "Oceania", code: "fm" },
        "Moldova": { region: "Europe", code: "md" },
        Mongolia: { region: "Asia", code: "mn" },
        Montenegro: { region: "Europe", code: "me" },
        Morocco: { region: "Africa", code: "ma" },
        Mozambique: { region: "Africa", code: "mz" },
        Myanmar: { region: "Asia", code: "mm" },
        Namibia: { region: "Africa", code: "na" },
        Nauru: { region: "Oceania", code: "nr" },
        Nepal: { region: "Asia", code: "np" },
        Netherlands: { region: "Europe", code: "nl" },
        "New Zealand": { region: "Oceania", code: "nz" },
        Nicaragua: { region: "Americas", code: "ni" },
        Nigeria: { region: "Africa", code: "ng" },
        Niger: { region: "Africa", code: "ne" },
        "North Macedonia": { region: "Europe", code: "mk" },
        Norway: { region: "Europe", code: "no" },
        Oman: { region: "Asia", code: "om" },
        Pakistan: { region: "Asia", code: "pk" },
        Palau: { region: "Oceania", code: "pw" },
        Panama: { region: "Americas", code: "pa" },
        "Papua New Guinea": { region: "Oceania", code: "pg" },
        Paraguay: { region: "Americas", code: "py" },
        Peru: { region: "Americas", code: "pe" },
        Philippines: { region: "Asia", code: "ph" },
        Poland: { region: "Europe", code: "pl" },
        Portugal: { region: "Europe", code: "pt" },
        Qatar: { region: "Asia", code: "qa" },
        Romania: { region: "Europe", code: "ro" },
        Russia: { region: "Europe", code: "ru" },
        Rwanda: { region: "Africa", code: "rw" },
        "Saint Kitts and Nevis": { region: "Americas", code: "kn" },
        "Saint Lucia": { region: "Americas", code: "lc" },
        "Saint Vincent and the Grenadines": { region: "Americas", code: "vc" },
        Samoa: { region: "Oceania", code: "ws" },
        "San Marino": { region: "Europe", code: "sm" },
        "Sao Tome and Principe": { region: "Africa", code: "st" },
        "Saudi Arabia": { region: "Asia", code: "sa" },
        Senegal: { region: "Africa", code: "sn" },
        Serbia: { region: "Europe", code: "rs" },
        Seychelles: { region: "Africa", code: "sc" },
        "Sierra Leone": { region: "Africa", code: "sl" },
        Singapore: { region: "Asia", code: "sg" },
        Slovakia: { region: "Europe", code: "sk" },
        Slovenia: { region: "Europe", code: "si" },
        "Solomon Islands": { region: "Oceania", code: "sb" },
        Somalia: { region: "Africa", code: "so" },
        "South Africa": { region: "Africa", code: "za" },
        "South Sudan": { region: "Africa", code: "ss" },
        Spain: { region: "Europe", code: "es" },
        "Sri Lanka": { region: "Asia", code: "lk" },
        Sudan: { region: "Africa", code: "sd" },
        Suriname: { region: "Americas", code: "sr" },
        Sweden: { region: "Europe", code: "se" },
        Switzerland: { region: "Europe", code: "ch" },
        Syria: { region: "Asia", code: "sy" },
        Taiwan: { region: "Asia", code: "tw" },
        Tajikistan: { region: "Asia", code: "tj" },
        "Tanzania": { region: "Africa", code: "tz" },
        Thailand: { region: "Asia", code: "th" },
        Tibet: { region: "Asia", code: "cn" },
        "Timor-Leste": { region: "Asia", code: "tl" },
        Togo: { region: "Africa", code: "tg" },
        Tonga: { region: "Oceania", code: "to" },
        "Trinidad and Tobago": { region: "Americas", code: "tt" },
        Tunisia: { region: "Africa", code: "tn" },
        Turkey: { region: "Asia", code: "tr" },
        Turkmenistan: { region: "Asia", code: "tm" },
        Uganda: { region: "Africa", code: "ug" },
        Ukraine: { region: "Europe", code: "ua" },
        "United Arab Emirates": { region: "Asia", code: "ae" },
        "United Kingdom": { region: "Europe", code: "gb" },
        "United States of America": { region: "Americas", code: "us" },
        Uruguay: { region: "Americas", code: "uy" },
        Uzbekistan: { region: "Asia", code: "uz" },
        Vanuatu: { region: "Oceania", code: "vu" },
        Venezuela: { region: "Americas", code: "ve" },
        "Viet Nam": { region: "Asia", code: "vn" },
        "Western Sahara": { region: "Africa", code: "eh" },
        Yemen: { region: "Asia", code: "ye" },
        Zambia: { region: "Africa", code: "zm" },
        Zimbabwe: { region: "Africa", code: "zw" },
      },
    },

    tooltips: {
      countries: '<div id="mapTooltip"><div>\n' +
                 '<span><b>Country: </b> <img class=mapflag width=9 height=6 src="/flags.jsp?c=${data.code}"> ${shapeId} (${data.code})</span><br>' +
                 "<span><b>Region: </b>${data.region}</span><br>\n" +
                 "<span><b>Routers: 0</b></span>\n" + "</div>\n </div>",
    },
  };

  let routerCount = 0;
  let routerCounts = {};

  function storeRouterCounts() {
    var url = "/netdb";
    var geoxhr = new XMLHttpRequest();
    geoxhr.responseType = "document";
    geoxhr.open("GET", url, true);
    geoxhr.onreadystatechange = function () {
      if (geoxhr.readyState === 4) {
        if (geoxhr.status === 200) {
          var rows = geoxhr.responseXML.querySelectorAll("tr");
          rows.forEach(function (row) {
            var country = row.querySelector('a[href^="/netdb?c="]');
            if (country) {
              var code = country.getAttribute("href").split("=")[1];
              // Get the total count, not floodfills or X tier for now
              // TODO: enable toggle for X and floodfill count
              if (row.children[3]) {
                routerCounts[code] = row.children[3].textContent.trim();
              }
            }
          });

          localStorage.setItem("routerCounts", JSON.stringify(routerCounts));
          Object.keys(m.data.countries).forEach((shapeId) => {
            updateShapeClass(shapeId);
          });
        } else {
          // Clear local storage on error and schedule another check
          localStorage.removeItem("routerCounts");
          setTimeout(storeRouterCounts, TIMEOUT);
        }
      }
    };
    geoxhr.send();
  }

  function getRouterCount(shapeId) {
    var code = (m.data.countries[shapeId] && m.data.countries[shapeId].code) || "";
    const count = routerCounts[code] || "0";
    updateShapeClass(shapeId, count);
    return routerCounts[code] || "0";
  }

  function updateShapeClass(shapeId, count) {
    const svgElement = document.getElementById(shapeId);
    if (!svgElement) {return;}

    const code = (m.data.countries[shapeId] && m.data.countries[shapeId].code) || "";
    count = routerCounts[code] || "0";
    const countThresholds = [
      { threshold: 1, className: "count_1" },
      { threshold: 10, className: "count_10" },
      { threshold: 50, className: "count_50" },
      { threshold: 100, className: "count_100" },
      { threshold: 200, className: "count_200" },
      { threshold: 300, className: "count_300" },
      { threshold: 400, className: "count_400" },
      { threshold: 500, className: "count_500" },
    ];

    // Remove existing count classes
    countThresholds.forEach(({ className }) => svgElement.classList.remove(className));

    // Find the highest threshold that is met and apply the corresponding class
    let highestThreshold = null;
    countThresholds.forEach(({ threshold, className }) => {
      if (parseInt(count) >= threshold) {
        highestThreshold = { threshold, className };
      }
    });

    if (highestThreshold) {
      svgElement.classList.add(highestThreshold.className);
    }
  }

  function createTooltip(data, tooltipTemplate, shapeId) {
    if (!data) return;
    const foreignObject = document.createElementNS("http://www.w3.org/2000/svg", "foreignObject");
    foreignObject.setAttribute("width", "1");
    foreignObject.setAttribute("height", "1");
    foreignObject.style.overflow = "visible";

    // Generate the tooltip HTML using the template string and eval statement
    const tooltipHTML = eval("`" + tooltipTemplate + "`");
    const htmlDoc = new DOMParser().parseFromString(tooltipHTML, "text/html");
    const tooltipElement = htmlDoc.querySelector("body");
    tooltipElement.style.background = "none";
    tooltipElement.style.pointerEvents = "none";

    // Set the tooltip element's style properties and append it to the foreignObject element
    requestAnimationFrame(() => {
      tooltipElement.style.position = "fixed";
      foreignObject.appendChild(tooltipElement);
    });

    return foreignObject;
  }

  function hideTooltip() {
    const tooltip = document.querySelector("#mapTooltip");
    if (!tooltip) {return;}
    tooltip.classList.add("hidden");
  }

  function debounce(func, wait, immediate) {
    let timeout;
    return function() {
      const context = this, args = arguments;
      const later = function () {
        timeout = null;
        if (!immediate) func.apply(context, args);
      };
      const callNow = immediate && !timeout;
      clearTimeout(timeout);
      timeout = setTimeout(later, wait);
      if (callNow) func.apply(context, args);
    };
  };

  const handleEvent = debounce(function(event) {
    const target = event.target;
    const targetParent = target.parentNode;

    // Check if the target is a path element and has an ID
    if (target && target.matches && target.hasAttribute && target.matches("path") && target.hasAttribute("id")) {
      const containerRect = geomap.getBoundingClientRect();
      const scaleWidth = width / containerRect.width;
      const scaleHeight = height / containerRect.height;
      const shapeRect = tooltipInfo.element.firstChild?.firstChild?.getBoundingClientRect();

      let xPosition = (event.clientX - containerRect.left + 10) * scaleWidth;
      let yPosition = (event.clientY - containerRect.top + 10) * scaleHeight;
      let opacity = 1;

      const shapeId = target.getAttribute("id");
      const sectionId = targetParent.getAttribute("id");

      if (shapeRect?.width > 0) {
        if (containerRect.right - shapeRect.width < event.clientX + 10) {
          xPosition = (event.clientX - containerRect.left - shapeRect.width - 20) * scaleWidth;
        }
        if (containerRect.top + shapeRect.height + 120 > event.clientY + 10) {
          yPosition = (event.clientY + containerRect.top + shapeRect.height + 20) * scaleHeight;
        } else if (containerRect.bottom - shapeRect.height < event.clientY + 10) {
          yPosition = (event.clientY - containerRect.top - shapeRect.height - 20) * scaleHeight;
        }
      } else if (shapeId && sectionId in m.data) {
        opacity = 0;
        setTimeout(() => {handleEvent(event);}, 0);
      }

      if (sectionId in m.data) {
        if (shapeId && tooltipInfo.shapeId === shapeId) {
          updateShapePosition(tooltipInfo.element, xPosition, yPosition, opacity);
        } else {
          const data = m.data[sectionId][shapeId];
          const routerCount = getRouterCount(shapeId);
          if (!data) {
            tooltipInfo.element.style.display = "none";
            return;
          }

          const newElement = createTooltip(data, m.tooltips[sectionId].replace(/<b>Routers: 0<\/b>/g, "<b>Routers: </b>" + routerCount + "</span>"),
                                           shapeId, scaleWidth, scaleHeight);
          requestAnimationFrame(() => {replaceAndSetNewElement(tooltipInfo.element, newElement, shapeId, xPosition, yPosition, opacity);});
        }
      }
    } else {hideTooltip();}
  }, DEBOUNCE_DELAY);

  function updateShapePosition(element, x, y, opacity) {
    element.setAttribute("x", x);
    element.setAttribute("y", y);
    element.firstChild.style.position = "absolute";
    setTimeout(() => {element.firstChild.style.position = "fixed";}, 0);
    element.style.display = "block";
    element.style.opacity = opacity;
  }

  function replaceAndSetNewElement(oldElement, newElement, shapeId, x, y, opacity) {
    oldElement.replaceWith(newElement);
    tooltipInfo.element = newElement;
    tooltipInfo.shapeId = shapeId;
    tooltipInfo.element.setAttribute("x", x);
    tooltipInfo.element.setAttribute("y", y);
    tooltipInfo.element.style.opacity = opacity;
  }

  function findPathIndex(path, container) {
    return Array.from(container.children).indexOf(path);
  }

  function movePathToBack(path, container) {
    container.appendChild(path);
  }

  function restorePathPosition(path, previousPos, container) {
    container.insertBefore(path, container.children[previousPos]);
  }

  let preloadedFlags = [];

  function preloadFlags(codes) {
    const flagContainer = document.createElement("div");
    flagContainer.style.display = "none";
    document.body.appendChild(flagContainer);

    codes.forEach(code => {
      if (!preloadedFlags.includes(code)) {
        const img = new Image();
        img.src = `/flags.jsp?c=${code}`;
        flagContainer.appendChild(img);
        preloadedFlags.push(code);
      }
    });
  }

  const codes = Object.values(m.data.countries).map(country => country.code);
  preloadFlags(codes);

  setInterval(storeRouterCounts, STORE_INTERVAL);
  storeRouterCounts();

  geomap.addEventListener("mouseenter", handleEvent);
  geomap.addEventListener("mouseout", (event) => {
    hideTooltip(event);
    if (event.target.previousPos) {
      const previousPos = event.target.previousPos;
      const container = event.target.parentNode;
      restorePathPosition(event.target, previousPos, container);
    }
  });

  geomap.addEventListener("mousemove", (event) => {
    handleEvent(event);
    const container = event.target.parentNode;
    if (event.target.tagName === "path" && container.tagName === "g") {
      const currentPathIndex = findPathIndex(event.target, container);
      if (!event.target.previousPos) {event.target.previousPos = currentPathIndex;}
      if (event.target !== container.lastElementChild) {movePathToBack(event.target, container);}
    }
  });

  function destroyTooltipHandling() {
    geomap.removeEventListener("mouseenter", handleEvent);
    geomap.removeEventListener("mouseout", (event) => {
      hideTooltip(event);
      if (event.target.previousPos) {
        const previousPos = event.target.previousPos;
        const container = event.target.parentNode;
        restorePathPosition(event.target, previousPos, container);
      }
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    Object.keys(m.data.countries).forEach((shapeId) => {
      const code = m.data.countries[shapeId].code || "";
      const routerCount = getRouterCount(shapeId);
      updateShapeClass(shapeId, routerCount);
    });
  });

  window.addEventListener("beforeunload", destroyTooltipHandling);

})();