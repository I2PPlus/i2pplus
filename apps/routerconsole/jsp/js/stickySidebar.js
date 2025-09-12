/* I2P+ StickySidebar by dr|z3d */
/* License: AGPLv3 or later */

function stickySidebar() {
  const html = document.querySelector("html");
  const htmlHeight = html.getBoundingClientRect().height;
  const sb = document.getElementById("sidebar");
  const sbHeight = sb.getBoundingClientRect().height;
  const sbWrap = document.getElementById("sb_wrap");
  const viewportHeight = window.visualViewport.height;
  const viewportWidth = window.visualViewport.width;
  const sectionToggle = document.querySelector("toggleSection");
  const iframe = document.querySelector(".embed");
  let debug = false;

  if (!sbWrap) {return;}

  function calcHeight() {
    if ((sbHeight + 5 < viewportHeight && (htmlHeight > viewportHeight) && viewportHeight > 700) ||
        (iframe !== null && iframe.getBoundingClientRect().height > viewportHeight && viewportHeight > 700)) {
      sbWrap.style.position = "sticky";
      sbWrap.style.top = "5px";
      sbWrap.classList.add("sticky");
    } else {
      sbWrap.style.position = null;
      sbWrap.style.top = null;
      sbWrap.classList.remove("sticky");
    }
    if (debug) {
      if (iframe !== null && iframe.getBoundingClientRect().height > viewportHeight) {
        console.log("Iframe height currently reported as:" + iframe.getBoundingClientRect().height);
      }
    }
  }
  calcHeight();

  if (iframe) { setTimeout(() => { calcHeight(); }, 1000); }
  else {calcHeight();}

  sbWrap.addEventListener("click", function(element) {
    if (element.target.className === "toggleSection") {calcHeight();}
  });

  window.addEventListener("resize", () => { calcHeight(); });

}

export {stickySidebar};