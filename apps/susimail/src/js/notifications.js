function initNotifications() {
	var buttons = document.getElementsByClassName("notifications");
	for(index = 0; index < buttons.length; index++)
	{
		var button = buttons[index];
		addClickHandler6(button);
	}
}

function addClickHandler6(elem)
{
	elem.addEventListener("click", function() {
		elem.remove();
	});
}

document.addEventListener("DOMContentLoaded", function() {
    initNotifications();
}, true);
