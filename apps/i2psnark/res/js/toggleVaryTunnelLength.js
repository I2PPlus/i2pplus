/* I2P+ I2PSnark toggleVaryTunnelLength.js by dr|z3d */
/* Hackish way to configure tunnel length variance in UI */
/* License: AGPL3 or later */

function toggleVaryTunnelLength(checkboxId, string) {
  const checkbox = document.getElementById(checkboxId);
  const input = document.getElementById("i2cpOpts");

  checkbox.addEventListener("change", () => {
    if (checkbox.checked) {
      if (checkboxId === "varyInbound" && input.value.includes("inbound.lengthVariance=0")) {
        input.value = input.value.replace("inbound.lengthVariance=0", "inbound.lengthVariance=1");
      } else if (checkboxId === "varyOutbound" && input.value.includes("outbound.lengthVariance=0")) {
        input.value = input.value.replace("outbound.lengthVariance=0", "outbound.lengthVariance=1");
      } else {
        input.value += ` ${string}`;
      }
    } else {
      if (checkboxId === "varyInbound" && input.value.includes("inbound.lengthVariance=1")) {
        input.value = input.value.replace("inbound.lengthVariance=1", "inbound.lengthVariance=0");
      } else if (checkboxId === "varyOutbound" && input.value.includes("outbound.lengthVariance=1")) {
        input.value = input.value.replace("outbound.lengthVariance=1", "outbound.lengthVariance=0");
      }
    }
  });
}

document.addEventListener("DOMContentLoaded", () => {
  toggleVaryTunnelLength("varyInbound", " inbound.lengthVariance=1");
  toggleVaryTunnelLength("varyOutbound", " outbound.lengthVariance=1");
});