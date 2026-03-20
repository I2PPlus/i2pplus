/**
 * @module togglePassword
 * @description Toggles password field visibility between plaintext and masked display.
 * @license AGPL3 or later
 */

/**
 * Handles the toggle click to show/hide the password field content.
 * Switches between "password" and "text" input types and updates button text.
 * @event toggle.onclick
 * @returns {void}
 */
toggle.onclick = function() {
    var x = document.getElementById("password");
    var toggle = document.getElementById("toggle");
    var hide = "Hide password";
    var show = "Show password";
    if (toggle.innerHTML === show) {
        toggle.innerHTML = hide;
        x.type="text";
        toggle.classList.add("hidepass");
        toggle.classList.remove("showpass");
    } else {
        toggle.innerHTML = show;
        x.type="password";
        toggle.classList.remove("hidepass");
        toggle.classList.add("showpass");
   }
}
