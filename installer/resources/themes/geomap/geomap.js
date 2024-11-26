/* I2P+ geomap.js by dr|z3d */
/* Heavily modified from https://cartosvg.com/mercator */

(function () {

  const geomap = document.querySelector("#geomap");
  const infobox = document.querySelector("#netdbmap #info");
  const currentRouterClass = getQueryParameter("class") || "countries";
  let debugging = false;
  let verbose = false;
  const parser = new DOMParser();
  const STORE_INTERVAL = 15000, DEBOUNCE_DELAY = 30, TIMEOUT = 15000;
  const width = 720, height = 475;

  const map = {
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
        Moldova: { region: "Europe", code: "md" },
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
        Tanzania: { region: "Africa", code: "tz" },
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

    infoboxHTML: {
      countries: '<span><b>Country: </b> <img class=mapflag width=9 height=6 src="/flags.jsp?c=${data.code}"> ${shapeId} (${data.code})</span><br>' +
        "<span><b>Region: </b>${data.region}</span><br>\n" + "<span><b>Routers: 0</b></span>\n",
    },
  };

  const preloadedFlags = new Set();
  function preloadFlags(codes) {
    const flagContainer = document.createElement("div");
    flagContainer.id = "preloadFlags";
    flagContainer.hidden = true;
    document.body.appendChild(flagContainer);
    const newImages = codes
      .filter(code => !preloadedFlags.has(code))
      .map(code => {
        const img = new Image();
        img.src = `/flags.jsp?c=${code}`;
        const errorMessage = `Failed to load flag for code: ${code}`;
        flagContainer.appendChild(img);
        return new Promise((resolve) => {
          img.onload = () => {
            preloadedFlags.add(code);
            resolve();
          };
        });
      });
    Promise.all(newImages).then(() => { document.body.removeChild(flagContainer); }).catch();
  }
  preloadFlags(Object.values(map.data.countries).map(country => country.code));

  let routerCounts = {};
  async function storeRouterCounts() {
    const url = "/netdb";
    try {
      const response = await fetch(url);
      if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
      const doc = new DOMParser().parseFromString(await response.text(), "text/html");
      const rows = doc.querySelectorAll("#cclist tr");
      routerCounts = JSON.parse(localStorage.getItem("routerCounts")) || { countries: {}, floodfill: {}, tierX: {} };

      rows.forEach(row => {
        const processRow = (type, selector, countSelector) => {
          const link = row.querySelector(selector);
          const count = row.querySelector(countSelector)?.textContent.trim();
          if (link && count) {
            const cc = link.href.match(/cc=([a-zA-Z]{2})/)?.[1];
            if (cc) routerCounts[type][cc] = parseInt(count, 10);
          }
        };
        processRow("tierX", 'td.countX a', 'td.countX');
        processRow("floodfill", 'td.countFF a', 'td.countFF');

        const country = row.querySelector('a[href^="/netdb?c="]');
        if (country && row.children[3]) {
          const cc = country.href.split("=")[1];
          routerCounts.countries[cc] = parseInt(row.children[3].textContent.trim(), 10);
        }
      });

      const totals = {
        countries: Object.values(routerCounts.countries).reduce((sum, count) => sum + count, 0),
        floodfill: Object.values(routerCounts.floodfill).reduce((sum, count) => sum + count, 0),
        tierX: Object.values(routerCounts.tierX).reduce((sum, count) => sum + count, 0)
      };

      routerCounts.totals = totals;

      if (debugging) {console.log(routerCounts);}
      localStorage.setItem("routerCounts", JSON.stringify(routerCounts));
      localStorage.setItem("currentRouterClass", currentRouterClass);
      updateShapeClasses(currentRouterClass);

      if (!storeRouterCounts.intervalId) {
        storeRouterCounts.intervalId = setInterval(() => {
          storeRouterCounts();
          updateShapeClasses(currentRouterClass);
        }, STORE_INTERVAL);
      }
    } catch (error) {
      if (debugging) console.error("Error fetching or processing data:", error);
      localStorage.removeItem("routerCounts");
      localStorage.removeItem("currentRouterClass");
      setTimeout(storeRouterCounts, TIMEOUT);
    }
  }

  function getRouterCount(shapeId, routerClass = "countries") {
    const code = (map.data.countries[shapeId]?.code) || "";
    const count = routerCounts[routerClass]?.[code] || 0;
    return count;
  }

  function getRouterTotalByClass(routerClass) {
    const routerCounts = JSON.parse(localStorage.getItem("routerCounts")) || {};
    return routerCounts.totals ? routerCounts.totals[routerClass] : 0;
  }

  function getQueryParameter(name) { return new URLSearchParams(window.location.search).get(name); }

  function changeRouterClass(routerClass) {
    localStorage.setItem("currentRouterClass", routerClass);
    window.location.href = `${window.location.pathname}?routerClass=${routerClass}`;
  }

  function setNavButtonActive() {
    const active = getQueryParameter("class");
    const navButtons = document.querySelectorAll("#nav span");
    navButtons.forEach(btn => btn.classList.remove("active"));
    const activeIdMap = {floodfill: "byff", tierX: "byX"};
    const activeId = activeIdMap[active] || "bycc";
    document.getElementById(activeId).classList.add("active");
  }

  function updateShapeClass(shapeId, count) {
    const svgElement = document.getElementById(shapeId);
    if (!svgElement) return;
    if (debugging && verbose) console.log(`Updating shapeId: ${shapeId}, count: ${count}`);

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

    const highestThreshold = countThresholds.reverse().find(({ threshold }) => count >= threshold);
    svgElement.classList.remove(...Array.from(svgElement.classList).filter(cls => cls.startsWith('count_')));
    if (highestThreshold) svgElement.classList.add(highestThreshold.className);
  }

  function updateShapeClasses(routerClass) {
    if (routerCounts[routerClass]) {
      Object.keys(map.data.countries).forEach(shapeId => {
        updateShapeClass(shapeId, getRouterCount(shapeId, routerClass));
      });
    }
  }

  function createInfobox(data, infoboxTemplate, shapeId, routerClass = "countries") {
    if (!data) return;
    const routerCount = getRouterCount(shapeId, routerClass);
    const tierName = routerClass !== "countries" ? routerClass.replace("tier", "Bandwidth tier ").replace("floodfill", "Floodfills") : "";
    infobox.innerHTML = infoboxTemplate
      .replace(/\$\{shapeId\}/g, shapeId)
      .replace(/\$\{data\.code\}/g, data.code)
      .replace(/\$\{data\.region\}/g, data.region)
      .replace(/<b>Routers: 0<\/b>/g, `<b>${tierName || "Routers"}:</b> ${routerCount}`)
      .replace("United States of America", "USA");
    infobox.classList.remove("hidden");
  }

  function renderTotals() {
    const totalCounts = {
      countries: getRouterTotalByClass("countries"),
      floodfill: getRouterTotalByClass("floodfill"),
      tierX: getRouterTotalByClass("tierX")
    };
    requestAnimationFrame(() => {
      infobox.innerHTML = `<b>Known ${currentRouterClass === "floodfill" ? "Floodfills" :
                                      currentRouterClass === "tierX" ? "X tier Routers" :
                                      "Routers"}:</b> ${totalCounts[currentRouterClass]}`;
    });
  }

  function debounce(func, wait, immediate = false) {
    let timeout;
    return function () {
      const context = this, args = arguments;
      clearTimeout(timeout);
      timeout = setTimeout(() => { if (!immediate) { func.apply(context, args); } }, wait);
      if (immediate && !timeout) { func.apply(context, args); }
    };
  }

  const handleEvent = debounce(event => {
    const target = event.target;
    const sectionId = target?.parentNode.id;
      if (target?.matches("path[id]")) {
        const shapeId = target.id;
        target.classList.add("hover");
        const data = map.data[sectionId]?.[shapeId];
      if (data) {createInfobox(data, map.infoboxHTML[sectionId], shapeId, currentRouterClass);}
    } else {renderTotals();}
  }, DEBOUNCE_DELAY);

  geomap.addEventListener("mouseenter", handleEvent);

  geomap.addEventListener("mouseout", ({ target }) => {
    if (target.previousPos) {
      const container = target.parentNode;
      container.insertBefore(target, container.children[target.previousPos]);
    }
    debounce(renderTotals, DEBOUNCE_DELAY);
  });

  geomap.addEventListener("mouseleave", () => { renderTotals(); });

  geomap.addEventListener("mousemove", event => {
    const { target } = event;
    const container = target.parentNode;
    handleEvent(event);
    if (target.tagName === "path" && container.tagName === "g") {
      const currentPathIndex = Array.from(container.children).indexOf(target);
      target.previousPos = target.previousPos ?? currentPathIndex;
      if (target !== container.lastElementChild) {container.appendChild(target);}
    }
  });

  document.addEventListener("DOMContentLoaded", () => {
    const routerClassFromURL = getQueryParameter("class") || "countries";
    localStorage.setItem("currentRouterClass", routerClassFromURL);
    storeRouterCounts();
    if (debugging) {console.log("Current routerClass is " + routerClassFromURL);}
    updateShapeClasses(routerClassFromURL);
    setNavButtonActive();
    setTimeout(() => { geomap.querySelector("#countries").removeAttribute("style"); }, 300);
  });

  window.addEventListener("beforeunload", () => {
    if (storeRouterCounts.intervalId) clearInterval(storeRouterCounts.intervalId);
  });

})();