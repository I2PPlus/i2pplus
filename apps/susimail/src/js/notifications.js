(function removeNotify() {
  document.addEventListener("DOMContentLoaded", () => {
    const notice = document.getElementById("notify");
    notice.addEventListener("click", () => {
      notice.remove();
      console.log("Notification nuked!");
    });
  });
})();