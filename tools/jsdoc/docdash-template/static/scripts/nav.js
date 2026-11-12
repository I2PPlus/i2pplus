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
