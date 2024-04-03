function remove100PercentHeight() {
  var elements = document.querySelectorAll("*");
  elements.forEach(function(element) {
    var style = element.style.cssText;
    if (style.toLowerCase().includes("height") && style.includes("100%")) {
      element.style.removeProperty("height");
    }
    // set divs without explicit margins to auto
    if (element.tagName.toLowerCase() === "div") {
      var marginStyle = getComputedStyle(element).getPropertyValue("margin");
      if (marginStyle === "0px") {
        element.style.margin = "auto";
      }
    }
    // change remote img src attribute to data-src, replace with inline blocked image icon
    if (element.tagName.toLowerCase() === "img") {
      if (!element.getAttribute("src").startsWith("data:")) {
        element.setAttribute("data-src", element.getAttribute("src"));
        element.setAttribute("src", "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Cg transform='translate(-5.33 -5.33)'%3E%3Cpath fill='%2374bbf0' d='M52.14 8H11.86C9.71 8 8 10.05 8 12.54v38.91C8 54.03 9.71 56 11.86 56h40.29c2.14 0 3.86-2.06 3.86-4.54V12.54C56 9.97 54.29 8 52.14 8z'/%3E%3Cpath fill='%23fff' d='M8 29.17a13.76 13.76 0 0 1 6.17-1.46c4.46 0 8.14 1.89 9.43 4.46 1.76-.62 3.62-.91 5.49-.86 5.49 0 10.03 2.57 10.63 6.09 4.71.34 8.4 2.91 8.57 6l-11.57 7.46H8z'/%3E%3Cpath fill='%236c994e' d='M21.2 45.54S13.49 43.4 8 48.28v5.57C8 55.14 9.29 56 10.91 56l11.57-6.6z'/%3E%3Cpath d='M8 51.11c5.57-4.8 13.2-2.66 13.2-2.66l.43 1.37.86-.43-1.29-3.86S13.49 43.4 8 48.28z' opacity='.1'/%3E%3Cpath fill='%237eb35b' d='M10.91 56c3.86-7.71 15.94-16.89 26.06-11.74l-3.43 8.14-8.4 3.6z'/%3E%3Cpath d='m36.63 45.11.34-.86C26.86 39.11 14.77 48.28 10.91 56h3.69c4.29-6.43 13.46-13.03 22.03-10.89z' opacity='.1'/%3E%3Cpath fill='%23a5eb78' d='M56 53.77V39.11C36.63 37.65 25.14 56 25.14 56h27.94c1.63 0 2.91-.94 2.91-2.23z'/%3E%3Cpath d='M56 41v-1.89C36.63 37.65 25.14 56 25.14 56H29c3.43-4.37 12.94-14.83 27-15z' opacity='.1'/%3E%3Ccircle cx='44.69' cy='19.31' r='8.57' fill='%23ffe97a'/%3E%3Cpath fill='red' d='M32 12.51a19.49 19.49 0 1 0 0 38.98 19.49 19.49 0 1 0 0-38.98zm0 4.87A14.62 14.62 0 0 1 46.62 32a14.62 14.62 0 0 1-2.44 7.92l-20.1-20.1A14.62 14.62 0 0 1 32 17.38zm-12.18 6.7 20.1 20.1A14.62 14.62 0 0 1 32 46.62 14.62 14.62 0 0 1 17.38 32a14.62 14.62 0 0 1 2.44-7.92z'/%3E%3C/g%3E%3C/svg%3E");
      }
    }
  });
  // set html background color
  document.documentElement.style.background = "#fff";
}

document.addEventListener("DOMContentLoaded", remove100PercentHeight);