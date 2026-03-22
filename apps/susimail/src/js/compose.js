/**
 * @module compose
 * @file SusiMail compose-window unload guard.
 * Prevents accidental navigation away from the compose page by prompting
 * the user before unload, unless a form submission button has been clicked.
 * @author dr|z3d
 * @license AGPL3 or later
 */

/** @type {boolean} */
let beforePopup = true;
window.addEventListener('beforeunload', (e)=>{if (beforePopup) { e.returnValue=true; }} );

/**
 * Iterates over all elements with class "beforePopup" and attaches a
 * click handler that disables the beforeunload prompt.
 * @function initPopup
 * @returns {void}
 */
function initPopup() {
	var buttons = document.getElementsByClassName("beforePopup");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler5(button);
	}
}

/**
 * Attaches a click listener to the given element that sets the global
 * {@link beforePopup} flag to false, suppressing the unload prompt.
 * @function addClickHandler5
 * @param {HTMLElement} elem - The element to bind the click handler to.
 * @returns {void}
 */
function addClickHandler5(elem)
{
	elem.addEventListener("click", function() {
		beforePopup = false;
	});
}

document.addEventListener("DOMContentLoaded", function() {
    initPopup();
}, true);
