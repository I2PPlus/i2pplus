/**
 * @module docdash-nav
 * @description Scrolls the shared nav to the entry for the page just loaded. Compares
 * file names, not whole hrefs: publish.js writes folder-prefixed links while the page's
 * own URL is only the last segment.
 * @see {@link https://github.com/clenemt/docdash}
 * @license Apache-2.0 (upstream docdash)
 */
function scrollToNavItem() {
    const path = window.location.href.split('/').pop().replace(/\.html/, '');
    document.querySelectorAll('nav a').forEach(function(link) {
      // the nav hrefs publish.js writes are folder-prefixed (router/PeerStats.js.html)
      // while the page's own URL is the last segment of the location, so compare
      // file names: comparing the whole href never matched anything on our pages
      const href = link.attributes.href.value.split('/').pop().replace(/\.html/, '');
      if (path === href) {
        link.scrollIntoView({block: 'center'});
      }
    })
  }

  scrollToNavItem();
