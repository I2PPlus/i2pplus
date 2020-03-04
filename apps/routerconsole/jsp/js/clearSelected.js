function clearSelected() {
    var elements = document.getElementsByClassName("togglestat");
    for (var i = 0; i < elements.length; i++) {
        elements[i].classList.remove("tab2");
    }
}