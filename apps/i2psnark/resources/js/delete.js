function initDelete()
{
	var main = document.getElementById("mainsection");
	main.addEventListener("click", function() {
		if (!event.target.matches('input')) return;
		var clname = event.target.className;
		if (clname == 'delete1') {
			if (!confirm(deleteMessage1.replace("{0}", event.target.getAttribute("client")))) {
				event.preventDefault();
				return false;
			}
		} else if (clname == 'delete2') {
			if (!confirm(deleteMessage2.replace("{0}", event.target.getAttribute("client")))) {
				event.preventDefault();
				return false;
			}
		}
	});
}

var main = document.getElementById("mainsection");
if (main) {
  main.addEventListener("mouseover", function() {
    initDelete();
  }, false);
}