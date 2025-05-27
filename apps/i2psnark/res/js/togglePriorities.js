/* I2P+ I2PSnark togglePriorities.js */
/* Toggle file download priorities for all displayed files */
/* Author: dr|z3d */

document.addEventListener("DOMContentLoaded", function() {
  const dirNav = document.getElementById("dirNav");
  const headerRow = dirNav ? document.querySelector("#dirInfo tbody tr:first-child") : document.querySelector("#dirInfo thead tr:last-child");
  const incomplete = document.querySelectorAll("#dirInfo tr.incomplete").length;
  if (!incomplete || incomplete < 2 || !headerRow) {return;}
  const priorityColumn = document.querySelector("#dirInfo td.priority");
  const columns = ["prihigh", "priskip"];
  const toggleRow = headerRow.cloneNode(false);
  const thSpacer = document.createElement("th");
  const thToggle = document.createElement("th");
  toggleRow.removeAttribute("class");
  toggleRow.id = "togglePriorities";
  thSpacer.setAttribute("colspan", "4");

  columns.forEach(function(column, index) {
    const toggleId = `toggle-${column}`;
    const toggle = document.createElement("input");
    const label = document.createElement("label");
    toggle.type = "checkbox";
    toggle.id = toggleId;
    toggle.style.opacity = "0";
    label.setAttribute("for", toggleId);
    label.appendChild(toggle);
    thToggle.appendChild(label);
  });

  const style = document.createElement("style");
  style.textContent = `
    label[for="toggle-prihigh"],label[for="toggle-priskip"]{height:24px;width:50%;display:inline-block;vertical-align:middle;background:url(/i2psnark/.res/icons/clock_red.png) no-repeat 30% center/18px;cursor:pointer}
    label[for="toggle-priskip"]{background:url(/i2psnark/.res/icons/block.png) no-repeat 60% center/18px}
  `;
  document.head.appendChild(style);
  toggleRow.appendChild(thToggle);
  toggleRow.insertBefore(thSpacer, thToggle);
  headerRow.parentNode.insertBefore(toggleRow, headerRow.nextSibling);

  document.body.addEventListener("click", function(event) {
    const target = event.target;
    if (target.matches("input[type='checkbox'][id^='toggle-']")) {
      const toggleId = target.id;
      const column = toggleId.substr(7);
      const radios = document.querySelectorAll(`#dirInfo .${column}`);
      const normalPriority = document.querySelectorAll("#dirInfo .prinorm");
      let allChecked = true;
      radios.forEach(function(radio) {
        if (!radio.checked) {allChecked = false;}
      });
      if (allChecked) {
        radios.forEach(function(radio) {
          radio.checked = false;
          normalPriority.forEach(function(radio) {radio.checked = true;});
        });
      } else {
        radios.forEach(function(radio) {radio.checked = target.checked;});
      }
      const checkedToggles = document.querySelectorAll("#dirInfo input[type=checkbox]:checked");
      if (checkedToggles.length === 0) {
        normalPriority.forEach(function(radio) {radio.checked = true;});
      }
      columns.forEach(function(col, i) {
        if (col !== column) {
          const otherToggle = document.getElementById(`toggle-${col}`);
          if (otherToggle.checked) {
            otherToggle.checked = false;
            const otherRadios = document.querySelectorAll(`#dirInfo .${col}`);
          }
        }
      });
    }
  });
});