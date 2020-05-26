function initScrollers() {

  // https://stackoverflow.com/a/18071824
  // https://stackoverflow.com/a/33193694

  window.smoothScroll = function(target) {
    var scrollContainer = target;
    do { //find scroll container
      scrollContainer = scrollContainer.parentNode;
      if (!scrollContainer) return;
      scrollContainer.scrollTop += 1;
    } while (scrollContainer.scrollTop == 0);

    var targetY = 0;
    do { //find the top of target relatively to the container
      if (target == scrollContainer) break;
      targetY += target.offsetTop;
    } while (target = target.offsetParent);

    scroll = function(c, a, b, i) {
      i++;
      if (i > 30) return;
      c.scrollTop = a + (b - a) / 30 * i;
      setTimeout(function() {
        scroll(c, a, b, i);
      }, 20);
    }
    // start scrolling
    scroll(scrollContainer, scrollContainer.scrollTop, targetY, 0);
  }

  // scroll wrapper logs to bottom
  function scrollToBottom (id) {
    var wrapper_log = document.getElementById(id);
    wrapper_log.scrollTop = wrapper_log.scrollHeight - wrapper_log.clientHeight;
  }

  function scrollToTop (id) {
    var div = document.getElementById(id);
    div.scrollTop = 0;
  }

}

document.addEventListener("DOMContentLoaded", function() {
  initScrollers();
}, false);
