/* I2P+ StickySidebar by dr|z3d */
/* License: AGPLv3 or later */
function stickySidebar() {
  const sbWrap = document.getElementById("sb_wrap");
  const sb = document.getElementById("sidebar");
  const iframe = document.querySelector(".embed");
  if (!sbWrap || !sb) return;

  function calcHeight() {
    const htmlHeight = document.documentElement.getBoundingClientRect().height;
    const sbHeight = sb.getBoundingClientRect().height;
    const viewportHeight = window.visualViewport ? window.visualViewport.height : window.innerHeight;

    if (((sbHeight + 5 < viewportHeight) && (htmlHeight > viewportHeight) && (viewportHeight > 700)) ||
        (iframe && iframe.getBoundingClientRect().height > viewportHeight && viewportHeight > 700)) {
      sbWrap.style.position = "sticky";
      sbWrap.style.top = "5px";
      sbWrap.classList.add("sticky");
    } else {
      sbWrap.style.position = "";
      sbWrap.style.top = "";
      sbWrap.classList.remove("sticky");
    }
  }

  if (iframe) {
    sbWrap.classList.remove("sticky");
    setTimeout(() => { calcHeight(); }, 1500);
  } else {
    calcHeight();
  }

  sbWrap.addEventListener("click", function(event) {
    if (event.target.classList.contains("toggleSection")) {
      setTimeout(() => { calcHeight(); }, 500);
    }
  });

  window.addEventListener("resize", calcHeight);
}

export { stickySidebar };
