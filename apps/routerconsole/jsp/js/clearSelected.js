/**
 * @module clearSelected
 * @description Removes the "tab2" CSS class from all elements with class "togglestat"
 * when a click handler is triggered.
 * @license AGPL3 or later
 */

/**
 * Initializes click handlers to clear selected tab styling from toggle elements.
 * @function init
 * @returns {void}
 */
function init() {

    /**
     * Removes the "tab2" class from all elements with class "togglestat".
     * @function clearSelected
     * @returns {void}
     */
    function clearSelected() {
        var elements = document.getElementsByClassName("togglestat");
        for (var i = 0; i < elements.length; i++) {
            elements[i].classList.remove("tab2");
        }
    }

    /**
     * Attaches a click handler to the given element that clears selected tabs.
     * @function addClickHandler1
     * @param {HTMLElement} elem - The element to attach the click handler to
     * @returns {void}
     */
    function addClickHandler1(elem) {
        elem.addEventListener("click", function() {
        clearSelected();
        });
    }
}

document.addEventListener("DOMContentLoaded", init);