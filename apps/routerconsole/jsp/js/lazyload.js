function lazyload() {
  const lazyelements = document.querySelectorAll('.lazy');
  observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (entry.intersectionRatio > 0) {
        entry.target.classList.add('lazyshow');
        entry.target.classList.remove('lazyhide');
      } else {
        entry.target.classList.remove('lazyshow');
        entry.target.classList.add('lazyhide');
      }
    });
  });
  lazyelements.forEach(entry => {
    observer.observe(entry);
  });
}

lazyload();