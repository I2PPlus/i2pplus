/* I2P+ StickySidebar by dr|z3d */
/* License: AGPLv3 or later */

function stickySidebar() {
  var html = document.querySelector("html");
  var htmlHeight = html.getBoundingClientRect().height;
  var sb = document.getElementById("sidebar");
  var sbHeight = sb.getBoundingClientRect().height;
  var sbWrap = document.getElementById("sb_wrap");
  var viewportHeight = window.visualViewport.height;
  var viewportWidth = window.visualViewport.width;
  var sectionToggle = document.querySelector("toggleSection");
  var iframe = document.querySelector(".embed");

  function calcHeight() {
    if (sbWrap) {
//      if ((sbHeight + 5) < viewportHeight && (htmlHeight > viewportHeight) && viewportWidth > 1500) {
      if ((sbHeight + 5) < viewportHeight && (htmlHeight > viewportHeight) ||
          (iframe !== null && iframe.getBoundingClientRect().height > viewportHeight)) {
        sbWrap.style.position = "sticky";
        sbWrap.style.top = "5px";
        sbWrap.classList.add("sticky");
      } else {
        sbWrap.style.position = null;
        sbWrap.style.top = null;
        sbWrap.classList.remove("sticky");
      }
      //if (iframe !== null && iframe.getBoundingClientRect().height > viewportHeight) {
      //  console.log("Iframe height currently reported as:" + iframe.getBoundingClientRect().height);
      //}
    }
  }
  calcHeight();

  sbWrap.addEventListener("click", function(element) {
    if (element.target.className === "toggleSection") {
      calcHeight();
    }
  });
}

export {stickySidebar};
