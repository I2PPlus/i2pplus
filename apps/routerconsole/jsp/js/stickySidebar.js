/* I2P+ StickySidebar by dr|z3d */
/* License: AGPLv3 or later */

function stickySidebar() {
  var html = document.querySelector("html");
  var htmlHeight = html.getBoundingClientRect().height;
  var sb = document.getElementById("sidebar");
  var sbHeight = sb.getBoundingClientRect().height;
  var sbWrap = document.getElementById("sb_wrap");
  var viewportHeight = window.visualViewport.height;

  if (sbWrap) {
    if ((sbHeight + 5) < viewportHeight && (htmlHeight > viewportHeight)) {
      sbWrap.style.position = "sticky";
      sbWrap.style.top = "5px";
      sbWrap.classList.add("sticky");
    } else {
      sbWrap.style.position = null;
      sbWrap.style.top = null;
      sbWrap.classList.remove("sticky");
    }
  }
}

export {stickySidebar};
