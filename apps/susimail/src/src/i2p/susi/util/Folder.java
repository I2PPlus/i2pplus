// License: GPLv2+. See docs/LICENSES.md
package i2p.susi.util;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.i2p.data.DataHelper;

/**
 * Manages an Object array with paging and sorting support.
 *
 * Create a folder, set contents with setElements(), add comparators with addSorter(),
 * select a sort method with setSortBy(), and retrieve the current page with
 * currentPageIterator().
 *
 * All public methods are synchronized.
 *
 * @author susi
 * @param <O> type of objects stored in this folder
 */
public class Folder<O extends Object> {

	public static final String PAGESIZE = "pager.pagesize";
	public static final int DEFAULT_PAGESIZE = 30;

	/**
	 * Enumeration defining sort direction for folder elements.
	 */
	public enum SortOrder {
		/** lowest to highest */
		DOWN,
		/** reverse sort, highest  to lowest */
		UP;
	}

	private int pages;
	private int pageSize;
	private int currentPage;
	private O[] elements;
	private final Map<String, Comparator<O>> sorter;
	private SortOrder sortingDirection;
	private Comparator<O> currentSorter;
	private String currentSortID;

	public Folder()
	{
		pages = 1;
		currentPage = 1;
		sorter = new HashMap<>();
		sortingDirection = SortOrder.DOWN;
	}

	/**
	 * Returns the current page number.
	 * Starts at 1, even if empty.
	 *
	 * @return the current page number
	 */
	public synchronized int getCurrentPage() {
		return currentPage;
	}

	/**
	 * Sets the current page number.
	 * Starts at 1.
	 *
	 * @param currentPage the page number to set (1-based)
	 */
	public synchronized void setCurrentPage(int currentPage) {
		if( currentPage >= 1 && currentPage <= pages )
			this.currentPage = currentPage;
	}

	/**
	 * Returns the number of elements in the folder.
	 *
	 * @return the number of elements
	 */
	public synchronized int getSize() {
		return elements != null ? elements.length : 0;
	}

	/**
	 * Returns the number of pages in the folder.
	 * Minimum of 1 even if empty.
	 *
	 * @return the number of pages
	 */
	public synchronized int getPages() {
		return pages;
	}

	/**
	 * Returns page size. If no page size has been set, returns the
	 * {@link #PAGESIZE} property value, or {@link #DEFAULT_PAGESIZE}.
	 *
	 * @return the page size
	 */
	public synchronized int getPageSize() {
		return pageSize > 0 ? pageSize : Config.getProperty( PAGESIZE, DEFAULT_PAGESIZE );
	}

	/**
	 * Set page size and recalculate page counts.
	 *
	 * @param pageSize the new page size
	 */
	public synchronized void setPageSize(int pageSize) {
		if( pageSize > 0 )
			this.pageSize = pageSize;
		update();
	}

	/**
	 * Recalculates variables.
	 */
	private void update() {
		if( elements != null ) {
			pages = elements.length / getPageSize();
			if( pages * getPageSize() < elements.length )
				pages++;
			if( currentPage > pages )
				currentPage = pages;
		}
		else {
			pages = 1;
			currentPage = 1;
		}
	}

	/**
	 * Sorts the elements according to the order given by {@link #addSorter(String, Comparator)}
	 * and {@link #setSortBy(String, SortOrder)}.
	 *
	 * @since public since 0.9.33
	 */
	public synchronized void sort()
	{
		if (currentSorter != null && elements != null && elements.length > 1)
			DataHelper.sort(elements, currentSorter);
	}

	/**
	 * Set the array of objects the folder should manage.
	 * Does NOT copy the array.
	 * Sorts the array if a sorter set.
	 *
	 * @param elements Array of Os.
	 */
	public synchronized void setElements( O[] elements )
	{
		if (elements.length > 0) {
			this.elements = elements;
			sort();
		} else {
			this.elements = null;
		}
		update();
	}

	/**
	 * Remove an element from the folder.
	 *
	 * @param element the element to remove
	 */
	public void removeElement(O element) {
		removeElements(Collections.singleton(element));
	}

	/**
	 * Remove elements from the folder.
	 *
	 * @param elems the collection of elements to remove
	 */
	public synchronized void removeElements(Collection<O> elems) {
		if (elements != null) {
			List<O> list = new ArrayList<>(Arrays.asList(elements));
			boolean shouldUpdate = false;
			for (O e : elems) {
				if (list.remove(e))
					shouldUpdate = true;
			}
			if (shouldUpdate) {
				elements = (O[]) list.toArray(new Object[list.size()]);
				update();  // will still be sorted
			}
		}
	}

	/**
	 * Add an element only if it does not already exist.
	 *
	 * @param element the element to add
	 * @return true if added
	 */
	public boolean addElement(O element) {
		return addElements(Collections.singletonList(element)) > 0;
	}

	/**
	 * Add elements only if they do not already exist.
	 * Re-sorts the array if a sorter is set and any elements are actually added.
	 *
	 * @param elems the list of elements to add
	 * @return number of elements added
	 */
	public synchronized int addElements(List<O> elems) {
		int added = 0;
		if (elements != null) {
			// delay copy until required
			List<O> list = null;
			for (O e : elems) {
				boolean found = false;
				for (int i = 0; i < elements.length; i++) {
					if (e.equals(elements[i])) {
						found = true;
						break;
					}
				}
				if (!found) {
					if (list == null) {
						list = new ArrayList<>(Arrays.asList(elements));
					}
					list.add(e);
				}
			}
			if (list != null) {
				added = list.size() - elements.length;
				setElements((O[]) list.toArray(new Object[list.size()]));
			}
		} else if (!elems.isEmpty()) {
			added = elems.size();
			setElements((O[]) (elems.toArray(new Object[added])));
		}
		return added;
	}

	/**
	 * Returns an iterator containing the elements on the current page.
         * This iterator is over a copy of the current page, and so
         * is thread safe w.r.t. other operations on this folder,
         * but will not reflect subsequent changes, and iter.remove()
         * will not change the folder.
         *
	 * @return Iterator containing the elements on the current page.
	 */
	public synchronized Iterator<O> currentPageIterator()
	{
		ArrayList<O> list = new ArrayList<>();
		if( elements != null ) {
			int pageSize = getPageSize();
			int offset = ( currentPage - 1 ) * pageSize;
			for( int i = 0; i < pageSize && offset >= 0 && offset < elements.length; i++ ) {
				list.add( elements[offset] );
				offset++;
			}
		}
		return list.iterator();
	}

	/**
	 * Turns folder to next page.
	 */
	public synchronized void nextPage()
	{
		currentPage++;
		if( currentPage > pages )
			currentPage = pages;
	}

	/**
	 * Turns folder to previous page.
	 */
	public synchronized void previousPage()
	{
		currentPage--;
		if( currentPage < 1 )
			currentPage = 1;
	}

	/**
	 * Sets folder to display first page.
	 */
	public synchronized void firstPage()
	{
		currentPage = 1;
	}

	/**
	 * Sets folder to display last page.
	 */
	public synchronized void lastPage()
	{
		currentPage = pages;
	}

	/**
	 * Adds a new sorter to the folder. You can sort the folder by
	 * calling {@link #setSortBy(String, SortOrder)} with the given id.
	 *
	 * @param id identifier for this comparator, used with {@link #setSortBy(String, SortOrder)}
	 * @param sorter a Comparator to sort the elements
	 */
	public synchronized void addSorter( String id, Comparator<O> sorter )
	{
		this.sorter.put( id, sorter );
	}

	/**
	 * Activates sorting by the chosen Comparator. The id must
	 * match the one stored with {@link #addSorter(String, Comparator)}.
	 * Sets the sorting direction of the folder.
	 *
	 * Warning: this does not do the actual sort, only addElements() and setElements() do.
	 *
	 * @param id identifier for the Comparator
	 * @param direction UP or DOWN. UP is reverse sort.
	 */
	public synchronized void setSortBy(String id, SortOrder direction)
	{
		sortingDirection = direction;
		currentSorter = sorter.get( id );
		if (currentSorter != null) {
			if (sortingDirection == SortOrder.UP)
				currentSorter = Collections.reverseOrder(currentSorter);
			currentSortID = id;
		} else {
			currentSortID = null;
		}
	}

	/**
	 * Get the ID of the current sort comparator.
	 *
	 * @return the current sort ID, or null if none
	 * @since 0.9.13
	 */
	public synchronized String getCurrentSortBy() {
		return currentSortID;
	}

	/**
	 * Get the current sorting direction.
	 *
	 * @return the current SortOrder (UP or DOWN)
	 * @since 0.9.13
	 */
	public synchronized SortOrder getCurrentSortingDirection() {
		return sortingDirection;
	}

	/**
	 * Returns the first element of the sorted folder.
	 *
	 * @return First element.
	 */
	public synchronized O getFirstElement()
	{
		return elements == null ? null : getElement( 0 );
	}

	/**
	 * Returns the last element of the sorted folder.
	 *
	 * @return Last element.
	 */
	public synchronized O getLastElement()
	{
		return elements == null ? null : getElement(  elements.length - 1 );
	}

	/**
	 * Gets index of an element in the array regardless of sorting direction.
	 *
	 * @param element the element to find
	 * @return the index, or -1 if not found
	 */
	private int getIndexOf( O element )
	{
		if( elements != null ) {
			for( int i = 0; i < elements.length; i++ )
				if( elements[i].equals( element ) )
					return i;
		}
		return -1;
	}

	/**
	 * Retrieves the next element in the sorted array.
	 *
	 * @param element the current element
	 * @return the next element, or null if this is the last
	 */
	public synchronized O getNextElement( O element )
	{
		O result = null;

		int i = getIndexOf( element );

		if( i != -1 && elements != null ) {
			i++;
			if( i >= 0 && i < elements.length )
				result = elements[i];
		}
		return result;
	}

	/**
	 * Retrieves the previous element in the sorted array.
	 *
	 * @param element the current element
	 * @return the previous element, or null if this is the first
	 */
	public synchronized O getPreviousElement( O element )
	{
		O result = null;

		int i = getIndexOf( element );

		if( i != -1 && elements != null ) {
			i--;
			if( i >= 0 && i < elements.length )
				result = elements[i];
		}
		return result;
	}
	/**
	 * Retrieves element at index i.
	 *
	 * @param i the index
	 * @return the element, or null if empty
	 */
	private O getElement( int i )
	{
		O result = null;

		if( elements != null ) {
			result = elements[i];
		}
		return result;
	}

	/**
	 * Returns true if the current page is the last page.
	 *
	 * @return true if on the last page
	 */
	public synchronized boolean isLastPage()
	{
		return currentPage == pages;
	}

	/**
	 * Returns true if the current page is the first page.
	 *
	 * @return true if on the first page
	 */
	public synchronized boolean isFirstPage()
	{
		return currentPage == 1;
	}

	/**
	 * Returns true if the element is the last in the sorted array.
	 *
	 * @param element the element to check
	 * @return true if the element is the last
	 */
	public synchronized boolean isLastElement( O element )
	{
		if( elements == null )
			return false;
		return elements[elements.length - 1].equals( element );
	}

	/**
	 * Returns true if the element is the first in the sorted array.
	 *
	 * @param element the element to check
	 * @return true if the element is the first
	 */
	public synchronized boolean isFirstElement( O element )
	{
		if( elements == null )
			return false;
		return elements[0].equals( element );
	}

	/**
	 * Returns the page this element is on, using the current sort, or 1 if not found.
	 *
	 * @param element the element to find
	 * @return the page number (1-based)
	 * @since 0.9.33
	 */
	public synchronized int getPageOf(O element)
	{
		if (pages <= 1)
			return 1;
		if (elements == null)
			return 1;
		int i = getIndexOf(element);
		if (i < 0)
			return 1;
		return 1 + (i / getPageSize());
	}
}
