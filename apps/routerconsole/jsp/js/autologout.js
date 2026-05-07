/**
 * @module autologout
 * @description Auto-logout on session expiry. Checks session validity periodically
 * and redirects to /logout if session is invalid or expired.
 * @author dr|z3d
 * @license AGPL3 or later
 */
(function() {
  /** @constant {number} */
  var CHECK_INTERVAL = 300000;
  /** @constant {string} */
  var LOGOUT_URL = '/logout';

  /**
   * Checks session validity via XHR and redirects to logout if expired.
   * @returns {void}
   */
  function checkSession() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/', true);
    xhr.onload = function() {
      if (xhr.status === 302 || xhr.status === 0) {
        var loc = xhr.getResponseHeader('Location') || '';
        if (loc.indexOf('/login') >= 0 || xhr.responseURL.indexOf('/login') >= 0) {
          window.location.href = LOGOUT_URL;
        }
      } else if (xhr.status === 200) {
        var body = xhr.responseText || '';
        if (body.indexOf('id="topbar"') === -1 && body.indexOf('session') === -1) {
          var loginLink = body.indexOf('/login') >= 0 || body.indexOf('Login') >= 0;
          if (loginLink) {
            window.location.href = LOGOUT_URL;
          }
        }
      }
    };
    xhr.onerror = function() {};
    xhr.send();
  }

  /**
   * Initializes the session check interval.
   * @returns {void}
   */
  function init() {
    setInterval(checkSession, CHECK_INTERVAL);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();