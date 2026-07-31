/**
 * @module autologout
 * @description Auto-redirect to login on session expiry. Polls /session/check
 * via SharedWorker and redirects to /login when the session is no longer valid.
 * @author dr|z3d
 * @license AGPL3 or later
 */

(function() {
  var CHECK_INTERVAL = 10000;
  var CHECK_URL = '/session/check';
  var MAX_FAILURES = 2;
  var failures = 0;

  var fetchWorker = new SharedWorker("/js/fetchWorker.js");
  fetchWorker.port.start();
  fetchWorker.port.onmessage = function(e) {
    if (e.data.isDown) {
      failures++;
      if (failures >= MAX_FAILURES) {
        window.location.href = '/login';
      }
    } else {
      failures = 0;
    }
  };

  function checkSession() {
    fetchWorker.port.postMessage({ url: CHECK_URL, force: true });
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
