function initToggleInfo() {
  const toggle = document.getElementById("toggleInfo");
  const tunnelInfos = document.getElementsByClassName("tunnelInfo");
  const isCollapsed = toggle.classList.contains("collapse");

  for (const info of tunnelInfos) {info.style.display = isCollapsed ? "none" : "table-row";}

  toggle.innerHTML = isCollapsed
    ? '<img src="/themes/console/dark/images/expand_hover.svg" title="Show Tunnel Info">'
    : '<img src="/themes/console/dark/images/collapse_hover.svg" title="Hide Tunnel Info">';

  toggle.classList.toggle("collapse");
}

export { initToggleInfo };