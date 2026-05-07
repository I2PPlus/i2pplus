/**
 * @module login
 * @description Login page interactivity: theme/language dropdowns and AJAX preference updates.
 * Uses XMLHttpRequest to POST preference changes to the login servlet
 * and reloads the page on success.
 * @author dr|z3d
 * @license AGPL3 or later
 */

(function() {
  /**
   * Send preference update via AJAX.
   * @param {string} key - Preference key (theme or lang)
   * @param {string} value - Preference value
   */
  function setPref(key, value) {
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/login?' + key + '=' + value, true);
    xhr.onload = function() {
      if (xhr.status === 200) {
        location.reload();
      }
    };
    xhr.onerror = function() {
      location.reload();
    };
    xhr.send();
  }

  /**
   * Initialize dropdowns and click handlers.
   */
  function initLoginPage() {
    document.querySelectorAll('.dropdown').forEach(function(dropdown) {
      dropdown.addEventListener('click', function(e) {
        e.stopPropagation();
        var content = dropdown.querySelector('.dropdown-content');
        var isOpen = content && content.style.display === 'block';
        document.querySelectorAll('.dropdown-content').forEach(function(c) {
          c.style.display = 'none';
        });
        if (!isOpen && content) {
          content.style.display = 'block';
        }
      });
    });

    document.querySelectorAll('.dropdown-content a').forEach(function(link) {
      link.addEventListener('click', function(e) {
        e.preventDefault();
        var param = link.getAttribute('data-param');
        var value = link.getAttribute('data-value');
        if (param && value) {
          setPref(param, value);
        }
      });
    });

    document.addEventListener('click', function() {
      document.querySelectorAll('.dropdown-content').forEach(function(content) {
        content.style.display = 'none';
      });
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initLoginPage);
  } else {
    initLoginPage();
  }
})();