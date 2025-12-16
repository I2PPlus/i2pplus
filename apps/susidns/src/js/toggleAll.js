/**
 * Toggle all checkboxes for selecting/deselecting all hosts
 */

function toggleAll(selectAll) {
    var checkboxes = document.querySelectorAll('input[type="checkbox"][name^="markedForDeletion"]');
    
    for (var i = 0; i < checkboxes.length; i++) {
        checkboxes[i].checked = selectAll;
        // Update ARIA attributes
        checkboxes[i].setAttribute('aria-checked', selectAll);
        // Trigger change event to update select count if it exists
        var event = new Event('change', { bubbles: true });
        checkboxes[i].dispatchEvent(event);
    }
    
    // Update select all link text
    var selectAllLink = document.getElementById('selectAll');
    if (selectAllLink) {
        if (selectAllLink.tagName === 'A') {
            selectAllLink.textContent = selectAll ? 'deselect all' : 'select all';
        }
    }
}