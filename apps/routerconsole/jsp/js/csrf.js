/**
 * @module csrf
 * @description CSRF protection wrappers for fetch and XMLHttpRequest.
 * Uses double-submit pattern: reads token from meta tag and cookie,
 * sends both in request headers for defense-in-depth.
 * @author dr|z3d
 * @license AGPL3 or later
 */
 * @description Provides CSRF protection wrappers for fetch and XMLHttpRequest.
 * Uses double-submit cookie pattern: reads token from both meta tag and cookie,
 * then sends both in request headers for defense-in-depth.
 * @requires fetch API
 * @requires XMLHttpRequest
 * @example
 * // Using csrfFetch (drop-in replacement for fetch)
 * csrfFetch('/configupdate', {method: 'POST', body: 'data'})
 *   .then(response => console.log(response));
 * @example
 * // Using csrfXHR (replacement for new XMLHttpRequest)
 * var xhr = csrfXHR();
 * xhr.open('POST', '/endpoint');
 * xhr.send('data');
 */

/**
 * Retrieves CSRF token from meta tag or hidden form input.
 * @function getCsrfTokenFromMeta
 * @returns {string|null} CSRF token string or null if not found
 */
function getCsrfTokenFromMeta() {
  var meta = document.querySelector('meta[name="csrf-token"]');
  if (meta) return meta.getAttribute('content');
  var input = document.querySelector('input[name="nonce"]');
  if (input) return input.value;
  input = document.querySelector('input[name="consoleNonce"]');
  if (input) return input.value;
  return null;
}

/**
 * Retrieves CSRF token from cookie.
 * @function getCsrfTokenFromCookie
 * @returns {string|null} CSRF token from cookie or null if not found
 */
function getCsrfTokenFromCookie() {
  var cookies = document.cookie.split(';');
  for (var i = 0; i < cookies.length; i++) {
    var cookie = cookies[i].trim();
    if (cookie.indexOf('csrfToken=') === 0) {
      return cookie.substring('csrfToken='.length);
    }
  }
  return null;
}

/**
 * Fetches resource with CSRF protection headers.
 * @function csrfFetch
 * @param {string} url - The URL to fetch
 * @param {Object} [options] - Fetch options
 * @param {string} [options.method='GET'] - HTTP method
 * @param {Object} [options.headers={}] - Additional headers
 * @param {BodyInit} [options.body] - Request body
 * @param {boolean} [options.credentials] - Include credentials
 * @returns {Promise<Response>} Fetch response promise
 * @example
 * // POST with data
 * csrfFetch('/api endpoint', {
 *   method: 'POST',
 *   body: JSON.stringify({key: 'value'})
 * });
 */
function csrfFetch(url, options) {
  options = options || {};
  options.headers = options.headers || {};
  if (csrfMetaToken) {
    options.headers['X-CSRF-Token'] = csrfMetaToken;
  }
  if (csrfCookieToken && csrfCookieToken !== csrfMetaToken) {
    options.headers['X-CSRF-Token-Cookie'] = csrfCookieToken;
  }
  return fetch(url, options);
}

/**
 * Creates XMLHttpRequest with CSRF protection headers.
 * @function csrfXHR
 * @returns {XMLHttpRequest} XMLHttpRequest instance with CSRF headers
 * @example
 * var xhr = csrfXHR();
 * xhr.open('POST', '/configupdate');
 * xhr.onreadystatechange = function() {
 *   if (xhr.readyState === 4 && xhr.status === 200) {
 *     console.log(xhr.responseText);
 *   }
 * };
 * xhr.send('data=1');
 */
function csrfXHR() {
  var xhr = new XMLHttpRequest();
  var originalOpen = xhr.open;
  xhr.open = function(method, url, async, user, password) {
    arguments[1] = url;
    var result = originalOpen.apply(this, arguments);
    if (csrfMetaToken) {
      this.setRequestHeader('X-CSRF-Token', csrfMetaToken);
    }
    if (csrfCookieToken && csrfCookieToken !== csrfMetaToken) {
      this.setRequestHeader('X-CSRF-Token-Cookie', csrfCookieToken);
    }
    return result;
  };
  return xhr;
}

// Initialize tokens at module load time
var csrfMetaToken = getCsrfTokenFromMeta();
var csrfCookieToken = getCsrfTokenFromCookie();

// Expose functions to global scope
window.csrfFetch = csrfFetch;
window.csrfXHR = csrfXHR;