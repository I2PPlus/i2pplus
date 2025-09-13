/* I2P+ StickySidebar by dr|z3d */
/* License: AGPLv3 or later */

function stickySidebar() {
  if (stickySidebarEnabled === false) return;
  const sbWrap = document.getElementById("sb_wrap");
  const sb = document.getElementById("sidebar");
  if (!sbWrap || !sb) return;

  function calcHeight() {
    const sbHeight = sb.getBoundingClientRect().height;
    const viewportHeight = window.visualViewport ? window.visualViewport.height : window.innerHeight;
    if (sbHeight + 5 < viewportHeight) {
      sbWrap.style.position = "sticky";
      sbWrap.style.top = "5px";
      sbWrap.classList.add("sticky");
    } else {
      sbWrap.style.position = "";
      sbWrap.style.top = "";
      sbWrap.classList.remove("sticky");
    }
  }

  calcHeight();

  sbWrap.addEventListener("click", (event) => {
    if (event.target.classList.contains("toggleSection")) { setTimeout(calcHeight, 500); }
  });

  window.addEventListener("resize", calcHeight);
}

export { stickySidebar };
