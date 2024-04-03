function remove100PercentHeight() {
  var elements = document.querySelectorAll("*");
  elements.forEach(function(element) {
    var style = element.style.cssText;
    if (style.toLowerCase().includes("height") && style.includes("100%")) {
      element.style.removeProperty("height");
    }
  });
  document.documentElement.style.background = "#fff";
}

document.addEventListener("DOMContentLoaded", remove100PercentHeight);