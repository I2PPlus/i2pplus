/**
 * @module autologout
 * @description Auto-redirect to login on session expiry. Polls /session/check
 * and redirects to /login when the session is no longer valid.
 * @author dr|z3d
 * @license AGPL3 or later
 */

(function() {
  /** @constant {number} check interval in ms (60 seconds) */
  var CHECK_INTERVAL = 60000;
  /** @constant {string} session validation endpoint */
  var CHECK_URL = '/session/check';
  /** @constant {number} consecutive failures before redirect */
  var MAX_FAILURES = 2;

  var failures = 0;

  function checkSession() {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', CHECK_URL, true);
    xhr.onload = function() {
      if (xhr.status === 401) {
        failures++;
        if (failures >= MAX_FAILURES) {
          window.location.href = '/login';
        }
      } else {
        failures = 0;
      }
    };
    xhr.onerror = function() {
      failures++;
      if (failures >= MAX_FAILURES) {
        window.location.href = '/login';
      }
    };
    xhr.send();
  }

  function init() {
    setInterval(checkSession, CHECK_INTERVAL);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
