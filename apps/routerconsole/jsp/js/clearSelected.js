function init() {

    function clearSelected() {
        var elements = document.getElementsByClassName("togglestat");
        for (var i = 0; i < elements.length; i++) {
            elements[i].classList.remove("tab2");
        }
    }

    function addClickHandler1(elem) {
        elem.addEventListener("click", function() {
        clearSelected();
        });
    }
}

document.addEventListener("DOMContentLoaded", function() {init()}, true);