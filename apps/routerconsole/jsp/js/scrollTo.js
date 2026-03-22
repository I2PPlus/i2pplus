/**
 * @module scrollTo
 * @description Provides smooth scrolling utilities including scroll-to-bottom,
 * scroll-to-top, and click-triggered navigation scrolling.
 * @license AGPL3 or later
 */

/**
 * Initializes scrolling functions and exposes smoothScroll on the window object.
 * @function initScrollers
 * @returns {void}
 */
function initScrollers() {

  // https://stackoverflow.com/a/18071824
  // https://stackoverflow.com/a/33193694

  /**
   * Smoothly scrolls to the target element within its scroll container.
   * Uses requestAnimationFrame-like step interpolation over 30 frames.
   * @function smoothScroll
   * @param {HTMLElement} target - The target element to scroll to
   * @returns {void}
   */
  window.smoothScroll = function(target) {
    var scrollContainer = target;
    do { //find scroll container
      scrollContainer = scrollContainer.parentNode;
      if (!scrollContainer) { return; }
      scrollContainer.scrollTop += 1;
    } while (scrollContainer.scrollTop === 0);

    var targetY = 0;
    do { //find the top of target relatively to the container
      if (target == scrollContainer) { break; }
      targetY += target.offsetTop;
      target = target.offsetParent;
    } while (target);

    scroll = function(c, a, b, i) {
      i++;
      if (i > 30) { return; }
      c.scrollTop = a + (b - a) / 30 * i;
      setTimeout(function() {
        scroll(c, a, b, i);
      }, 20);
    }
    // start scrolling
    scroll(scrollContainer, scrollContainer.scrollTop, targetY, 0);
  }

  /**
   * Scrolls the element with the given ID to the bottom.
   * @function scrollToBottom
   * @param {string} id - The ID of the element to scroll
   * @returns {void}
   */
  function scrollToBottom (id) {
    var wrapper_log = document.getElementById(id);
    wrapper_log.scrollTop = wrapper_log.scrollHeight - wrapper_log.clientHeight;
  }

  /**
   * Scrolls the element with the given ID to the top.
   * @function scrollToTop
   * @param {string} id - The ID of the element to scroll
   * @returns {void}
   */
  function scrollToTop (id) {
    var div = document.getElementById(id);
    div.scrollTop = 0;
  }

  /**
   * Initializes click handlers for elements with the "scrollToNav" class.
   * @function initScrollToNav
   * @returns {void}
   */
  function initScrollToNav() {
    var inputs = document.getElementsByClassName("scrollToNav");
    for (index = 0; index < inputs.length; index++) {
      var input = inputs[index];
      addScrollHander(input);
    }
  }

  /**
   * Attaches a click handler that triggers smooth scrolling.
   * @function addScrollHander
   * @param {HTMLElement} elem - The element to attach the click handler to
   * @returns {void}
   */
  function addScrollHander(elem) {
    elem.addEventListener("click", function() {
      scrollToNav(elem);
    });
  }

  /**
   * Triggers smooth scroll to the top of the page.
   * @function scrollToNav
   * @param {HTMLElement} element - The element that triggered the scroll
   * @returns {void}
   */
  function scrollToNav(element) {
    smoothScroll();
  }

}

document.addEventListener("DOMContentLoaded", function() {
  initScrollers();
}, false);
