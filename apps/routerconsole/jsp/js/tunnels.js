const nav = document.querySelector(".confignav");
const toggle = document.getElementById("toggleTunnels");

function applyHiddenClass() {
  const tunnelElements = document.querySelectorAll(".tunnel_id");
  const tunnelVisibility = localStorage.getItem("tunnelVisibility");

  if (tunnelVisibility !== "visible") {
    tunnelElements.forEach(function(tunnelElement) {
      tunnelElement.classList.add("hidden");
    });

    if (!toggle.classList.contains("off")) {
      toggle.classList.add("off");
    }
  } else if (toggle.classList.contains("off")) {
    toggle.classList.remove("off");
  }
}

document.addEventListener("DOMContentLoaded", function() {
  applyHiddenClass();

  const visibility = document.visibilityState;
  if (visibility === "visible") {
    setInterval(function() {
      const xhrtunn = new XMLHttpRequest();
      xhrtunn.open('GET', '/tunnels', true);
      xhrtunn.responseType = "document";
      xhrtunn.onload = function () {
        const tunnels = document.getElementById("tunnelsContainer");
        const tunnelsResponse = xhrtunn.responseXML.getElementById("tunnelsContainer");
        const tunnelsParent = tunnels.parentNode;
        if (!Object.is(tunnels.innerHTML, tunnelsResponse.innerHTML)) {
          tunnelsParent.replaceChild(tunnelsResponse, tunnels);
          applyHiddenClass();
        }
      }
      xhrtunn.send();
    }, 15000);
  }
});

nav.addEventListener("click", function(event) {
  const isOff = toggle.classList.contains("off");

  if (event.target.id === "toggleTunnels") {
    const tunnelElements = document.querySelectorAll(".tunnel_id");

    if (tunnelElements.length > 0) {
      const firstTunnelElement = tunnelElements[0];
      const isHidden = firstTunnelElement.classList.contains("hidden");

      tunnelElements.forEach(function(tunnelElement) {
        tunnelElement.classList.toggle("hidden", !isHidden);
      });

      event.target.classList.toggle("off");

      if (isHidden) {
        localStorage.setItem("tunnelVisibility", "visible");
      } else {
        localStorage.removeItem("tunnelVisibility");
      }
    }
  }

  if (event.target.id === "toggleTunnels") {
    toggle.classList.toggle("off", !isOff);
  }
});