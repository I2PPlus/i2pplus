/*
Copyright (c) 2006, Matthew Estes
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

	* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
	* Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
	* Neither the name of Metanotion Software nor the names of its
contributors may be used to endorse or promote products derived from this
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package net.metanotion.io.block.index;

import java.io.IOException;

import net.metanotion.io.Serializer;
import net.metanotion.io.block.BlockFile;
import net.metanotion.util.skiplist.SkipList;
import net.metanotion.util.skiplist.SkipSpan;


/**
 * I2P version of BSkipSpan
 *
 * BSkipSpan stores all keys and values in-memory, backed by the file.
 * IBSkipSpan stores only the first key, and no values, in-memory.
 *
 * For a get(), here we do a linear search through the span in the file 
 * and load only the found value (super() does a binary search in-memory).
 *
 * For a put() or remove(), we load all keys and values for the span from
 * the file, make the modification, flush() out the keys and values,
 * and null out the keys and values in-memory.
 *
 * Recommended span size is 16.
 *
 * @author zzz
 */
public class IBSkipSpan<K extends Comparable<? super K>, V> extends BSkipSpan<K, V> {

	private K firstKey;

	@Override
	@SuppressWarnings("unchecked")
	public SkipSpan<K, V> newInstance(SkipList<K, V> sl) {
		if (bf.log.shouldDebug())
			bf.log.debug("Splitting page " + this.page + " containing " + this.nKeys + '/' + this.spanSize);
		try {
			int newPage = bf.allocPage();
			init(bf, newPage, bf.spanSize);
			SkipSpan<K, V> rv = new IBSkipSpan<K, V>(bf, (BSkipList<K, V>) sl, newPage, keySer, valSer);
			// this is called after a split, so we need the data arrays initialized
			rv.keys = (K[]) new Comparable[bf.spanSize];
			rv.vals = (V[]) new Object[bf.spanSize];
			return rv;
		} catch (IOException ioe) { throw new RuntimeException("Error creating database page", ioe); }
	}

	/**
	 * Flush to disk and null out in-memory keys and values, saving only the first key
	 */
	@Override
	public void flush() {
		super.flush();
		if (nKeys <= 0)
			this.firstKey = null;
		if (keys != null) {
			if (nKeys > 0)
				this.firstKey = keys[0];
			this.keys = null;
			this.vals = null;
			if (bf.log.shouldDebug())
				bf.log.debug("Flushed data for page " + this.page + " containing " + this.nKeys + '/' + this.spanSize);
		} else if (bf.log.shouldDebug()) {
			// if keys is null, we are (hopefully) just updating the prev/next pages on an unloaded span
			bf.log.debug("Flushed pointers for for unloaded page " + this.page + " containing " + this.nKeys + '/' + this.spanSize);
		}
	}

	/**
	 * I2P - second half of load()
	 * Load the whole span's keys and values into memory
	 */
	@Override
	protected void loadData() throws IOException {
		super.loadData();
		if (this.nKeys > 0)
			this.firstKey = this.keys[0];
		if (bf.log.shouldDebug())
			bf.log.debug("Loaded data for page " + this.page + " containing " + this.nKeys + '/' + this.spanSize + " first key: " + this.firstKey);
	}

	/**
	 * Must already be seeked to the end of the span header
         * via loadInit() or seekData()
	 */
	private void loadFirstKey() throws IOException {
		if (this.nKeys <= 0)
			return;
		int ksz;
		int curPage = this.page;
		int[] curNextPage = new int[1];
		curNextPage[0] = this.overflowPage;
		int[] pageCounter = new int[1];
		pageCounter[0] = HEADER_LEN;
		ksz = this.bf.file.readUnsignedShort();
		this.bf.file.skipBytes(2);  //vsz
		pageCounter[0] +=4;
		byte[] k = new byte[ksz];
		curPage = this.bf.readMultiPageData(k, curPage, pageCounter, curNextPage);
		this.firstKey = this.keySer.construct(k);
		if (this.firstKey == null) {
			bf.log.error("Null deserialized first key in page " + curPage);
			repair(1);
		}
		if (bf.log.shouldDebug())
			bf.log.debug("Loaded header for page " + this.page + " containing " + this.nKeys + '/' + this.spanSize + " first key: " + this.firstKey);
	}

	/**
	 * Seek past the span header
	 */
	private void seekData() throws IOException {
		if (isKilled)
			throw new IOException("Already killed! " + this);
		BlockFile.pageSeek(this.bf.file, this.page);
		int magic = bf.file.readInt();
		if (magic != MAGIC)
			throw new IOException("Bad SkipSpan magic number 0x" + Integer.toHexString(magic) + " on page " + this.page);
		// 3 ints and 2 shorts
		this.bf.file.skipBytes(HEADER_LEN - 4);
	}

	/**
	 * Seek to the start of the span and load the data
	 * Package private so BSkipIterator can call it
	 */
	void seekAndLoadData() throws IOException {
		seekData();
		loadData();
	}

	/**
	 * Linear search through the span in the file for the value.
	 */
	private V getData(K key) throws IOException {
		seekData();
		int curPage = this.page;
		int[] curNextPage = new int[1];
		curNextPage[0] = this.overflowPage;
		int[] pageCounter = new int[1];
		pageCounter[0] = HEADER_LEN;
		int fail = 0;
		//System.out.println("Span Load " + sz + " nKeys " + nKeys + " page " + curPage);
		for(int i=0;i<this.nKeys;i++) {
			if((pageCounter[0] + 4) > BlockFile.PAGESIZE) {
				BlockFile.pageSeek(this.bf.file, curNextPage[0]);
				int magic = bf.file.readInt();
				if (magic != BlockFile.MAGIC_CONT) {
					bf.log.error("Lost " + (this.nKeys - i) + " entries - Bad SkipSpan magic number 0x" + Integer.toHexString(magic) + " on page " + curNextPage[0]);
					lostEntries(i, curPage);
					break;
				}
				curPage = curNextPage[0];
				curNextPage[0] = this.bf.file.readUnsignedInt();
				pageCounter[0] = CONT_HEADER_LEN;
			}
			int ksz = this.bf.file.readUnsignedShort();
			int vsz = this.bf.file.readUnsignedShort();
			pageCounter[0] +=4;
			byte[] k = new byte[ksz];
			try {
				curPage = this.bf.readMultiPageData(k, curPage, pageCounter, curNextPage);
			} catch (IOException ioe) {
				bf.log.error("Lost " + (this.nKeys - i) + " entries - Error loading [" + this + "] on page " + curPage, ioe);
				lostEntries(i, curPage);
				break;
			}
			//System.out.println("i=" + i + ", Page " + curPage + ", offset " + pageCounter[0] + " ksz " + ksz + " vsz " + vsz);
			K ckey = this.keySer.construct(k);
			if (ckey == null) {
				// skip the value and keep going
				curPage = this.bf.skipMultiPageBytes(vsz, curPage, pageCounter, curNextPage);
				bf.log.error("Null deserialized key in entry " + i + " page " + curPage);
				fail++;
				continue;
			}
			int diff = ckey.compareTo(key);
			if (diff == 0) {
				//System.err.println("Found " + key + " at " + i + " (first: " + this.firstKey + ')');
				byte[] v = new byte[vsz];
				try {
					curPage = this.bf.readMultiPageData(v, curPage, pageCounter, curNextPage);
				} catch (IOException ioe) {
					bf.log.error("Lost " + (this.nKeys - i) + " entries - Error loading [" + this + "] on page " + curPage, ioe);
					lostEntries(i, curPage);
					break;
				}
				V rv = this.valSer.construct(v);
				if (rv == null) {
					bf.log.error("Null deserialized value in entry " + i + " page " + curPage +
					                    " key=" + ckey);
					fail++;
				}
				if (fail > 0)
					repair(fail);
				return rv;
			}
			if (diff > 0) {
				//System.err.println("NOT Found " + key + " at " + i + " (first: " + this.firstKey + " current: " + ckey + ')');
				if (fail > 0)
					repair(fail);
				return null;
			}
			// skip the value and keep going
			curPage = this.bf.skipMultiPageBytes(vsz, curPage, pageCounter, curNextPage);
		}
		//System.err.println("NOT Found " + key + " at end (first: " + this.firstKey + ')');
		if (fail > 0)
			repair(fail);
		return null;
	}

        private void repair(int fail) {
	/*****  needs work
		try {
			loadData(false);
			if (this.nKeys > 0)
				this.firstKey = this.keys[0];
			flush();
			bf.log.error("Repaired corruption of " + fail + " entries");
		} catch (IOException ioe) {
			bf.log.error("Failed to repair corruption of " + fail + " entries", ioe);
		}
	*****/
	}

	private IBSkipSpan(BlockFile bf, BSkipList<K, V> bsl) {
		super(bf, bsl);
	}

	public IBSkipSpan(BlockFile bf, BSkipList<K, V> bsl, int spanPage, Serializer<K> key, Serializer<V> val) throws IOException {
		super(bf, bsl);
		if (bf.log.shouldDebug())
			bf.log.debug("New ibss page " + spanPage);
		BSkipSpan.loadInit(this, bf, bsl, spanPage, key, val);
		loadFirstKey();
		this.next = null;
		this.prev = null;

		IBSkipSpan<K, V> bss = this;
		IBSkipSpan<K, V> temp;
		int np = nextPage;
		while(np != 0) {
			temp = (IBSkipSpan<K, V>) bsl.spanHash.get(Integer.valueOf(np));
			if(temp != null) {
				bss.next = temp;
				break;
			}
			bss.next = new IBSkipSpan<K, V>(bf, bsl);
			bss.next.next = null;
			bss.next.prev = bss;
			K previousFirstKey = bss.firstKey;
			bss = (IBSkipSpan<K, V>) bss.next;
			
			BSkipSpan.loadInit(bss, bf, bsl, np, key, val);
			bss.loadFirstKey();
			K nextFirstKey = bss.firstKey;
			if (previousFirstKey == null || nextFirstKey == null ||
			    previousFirstKey.compareTo(nextFirstKey) >= 0) {
				// TODO remove, but if we are at the bottom of a level
				// we have to remove the level too, which is a mess
				bf.log.error("Corrupt database, span out of order " + ((BSkipSpan)bss.prev).page +
				                    " first key " + previousFirstKey +
				                    " next page " + bss.page +
				                    " first key " + nextFirstKey);
			}
			np = bss.nextPage;
		}

		// Go backwards to fill in the rest. This never happens.
		bss = this;
		np = prevPage;
		while(np != 0) {
			temp = (IBSkipSpan<K, V>) bsl.spanHash.get(Integer.valueOf(np));
			if(temp != null) {
				bss.prev = temp;
				break;
			}
			bss.prev = new IBSkipSpan<K, V>(bf, bsl);
			bss.prev.next = bss;
			bss.prev.prev = null;
			K nextFirstKey = bss.firstKey;
			bss = (IBSkipSpan<K, V>) bss.prev;
			
			BSkipSpan.loadInit(bss, bf, bsl, np, key, val);
			bss.loadFirstKey();
			K previousFirstKey = bss.firstKey;
			if (previousFirstKey == null || nextFirstKey == null ||
			    previousFirstKey.compareTo(nextFirstKey) >= 0) {
				// TODO remove, but if we are at the bottom of a level
				// we have to remove the level too, which is a mess
				bf.log.error("Corrupt database, span out of order " + bss.page +
				                    " first key " + previousFirstKey +
				                    " next page " + ((BSkipSpan)bss.next).page +
				                    " first key " + nextFirstKey);
			}
			np = bss.prevPage;
		}
	}

	/**
         * Does not call super, we always store first key here
	 */
	@Override
	public K firstKey() {
		return this.firstKey;
	}

	/**
	 * Load whole span from file, do the operation, flush out, then null out in-memory data again.
	 * This is called only via SkipList.find()
	 */
	@Override
	public SkipSpan<K, V> getSpan(K key, int[] search) {
		try {
			seekAndLoadData();
		} catch (IOException ioe) {
			throw new RuntimeException("Error reading database", ioe);
		}
		SkipSpan<K, V> rv = super.getSpan(key, search);
		this.keys = null;
		this.vals = null;
		return rv;
	}

	/**
	 * Linear search if in file, Binary search if in memory
	 */
	@Override
	public V get(K key) {
		try {
			if (nKeys == 0) { return null; }
			if (this.next != null && this.next.firstKey().compareTo(key) <= 0)
				return next.get(key);
			return getData(key);
		} catch (IOException ioe) {
			throw new RuntimeException("Error reading database", ioe);
		}
	}

	/**
	 * Load whole span from file, do the operation, flush out, then null out in-memory data again.
	 */
	@Override
	public SkipSpan<K, V> put(K key, V val, SkipList<K, V> sl)	{
		try {
			seekAndLoadData();
		} catch (IOException ioe) {
			throw new RuntimeException("Error reading database", ioe);
		}
		SkipSpan<K, V> rv = super.put(key, val, sl);
		// flush() nulls out the data
		return rv;
	}

	/**
	 * Load whole span from file, do the operation, flush out, then null out in-memory data again.
	 */
	@Override
	public Object[] remove(K key, SkipList<K, V> sl) {
		if (bf.log.shouldDebug())
			bf.log.debug("Remove " + key + " in " + this);
		if (nKeys <= 0)
			return null;
		try {
			seekAndLoadData();
			if (this.nKeys == 1 && this.prev == null && this.next != null && this.next.keys == null) {
				// fix for NPE in SkipSpan if next is not loaded
				if (bf.log.shouldInfo())
					bf.log.info("Loading next data for remove");
				((IBSkipSpan)this.next).seekAndLoadData();
			}
		} catch (IOException ioe) {
			throw new RuntimeException("Error reading database attempting to remove " + key, ioe);
		}
		Object[] rv = super.remove(key, sl);
		// flush() nulls out the data
		return rv;
	}
}
