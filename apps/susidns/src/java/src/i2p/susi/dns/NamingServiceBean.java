/*
 *  This file is part of susidns project, see http://susi.i2p/
 *  
 *  Copyright (C) 2005 <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 */

package i2p.susi.dns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.SortedMap;

import net.i2p.client.naming.NamingService;
import net.i2p.client.naming.SingleFileNamingService;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.servlet.RequestWrapper;

/**
 *  Talk to the NamingService API instead of modifying the hosts.txt files directly,
 *  except for the 'published' addressbook.
 *
 *  @since 0.8.7
 */
public class NamingServiceBean extends AddressbookBean
{
	private static final String DEFAULT_NS = "BlockfileNamingService";
	private String detail;
	private String notes;

	private boolean isDirect() {
		return getBook().equals("published");
	}

	@Override
	protected boolean isPrefiltered() {
		if (isDirect())
			return super.isPrefiltered();
		return (search == null || search.length() <= 0) &&
		       (filter == null || filter.length() <= 0) &&
		       getNamingService().getName().equals(DEFAULT_NS);
	}

	@Override
	protected int resultSize() {
		if (isDirect())
			return super.resultSize();
		return isPrefiltered() ? totalSize() : entries.length;
	}

	@Override
	protected int totalSize() {
		if (isDirect())
			return super.totalSize();
		// only blockfile needs the list property
		Properties props = new Properties();
		props.setProperty("list", getFileName());
		return getNamingService().size(props);
	}

	@Override
	public boolean isNotEmpty()
	{
		if (isDirect())
			return super.isNotEmpty();
		return totalSize() > 0;
	}

	@Override
	public String getFileName()
	{
		if (isDirect())
			return super.getFileName();
		loadConfig();
		String filename = properties.getProperty( getBook() + "_addressbook" );
		if (filename == null)
			return getBook();
		return basename(filename);
	}

	@Override
	public String getDisplayName()
	{
		if (isDirect())
			return super.getDisplayName();
		loadConfig();
		return _t("{0} address book in {1} database", getFileName(), getNamingService().getName());
	}

	/** depth-first search */
	private static NamingService searchNamingService(NamingService ns, String srch)
	{
		String name = ns.getName();
		if (name.equals(srch) || basename(name).equals(srch) || name.equals(DEFAULT_NS))
			return ns;
		List<NamingService> list = ns.getNamingServices();
		if (list != null) {
			for (NamingService nss : list) {
				NamingService rv = searchNamingService(nss, srch);
				if (rv != null)
					return rv;
			}
		}
		return null;		
	}

	private static String basename(String filename) {
		int slash = filename.lastIndexOf('/');
		if (slash >= 0)
			filename = filename.substring(slash + 1);
		return filename;
	}

	/** @return the NamingService for the current file name, or the root NamingService */
	private NamingService getNamingService()
	{
		NamingService root = _context.namingService();
		NamingService rv = searchNamingService(root, getFileName());		
		return rv != null ? rv : root;		
	}

	/**
	 *  Load addressbook and apply filter, returning messages about this.
	 *  To control memory, don't load the whole addressbook if we can help it...
	 *  only load what is searched for.
	 */
	@Override
	public String getLoadBookMessages()
	{
		if (isDirect())
			return super.getLoadBookMessages();
		NamingService service = getNamingService();
		debug("Searching within " + service + " with filename=" + getFileName() + " and with filter=" + filter + " and with search=" + search);
		String message = "";
		try {
			LinkedList<AddressBean> list = new LinkedList<AddressBean>();
			Map<String, Destination> results;
			Properties searchProps = new Properties();
			// only blockfile needs this
			boolean sortByDate = "latest".equals(filter);
			searchProps.setProperty("list", getFileName());
			if (filter != null && !sortByDate) {
				String startsAt = filter.equals("0-9") ? "[0-9]" : filter;
				searchProps.setProperty("startsWith", startsAt);
			}
			if (isPrefiltered()) {
				// Only limit if we not searching or filtering, so we will
				// know the total number of results
				if (beginIndex > 0)
					searchProps.setProperty("skip", Integer.toString(beginIndex));
				int limit = 1 + endIndex - beginIndex;
				if (limit > 0)
					searchProps.setProperty("limit", Integer.toString(limit));
			}
			if (search != null && search.length() > 0)
				searchProps.setProperty("search", search.toLowerCase(Locale.US));
			results = service.getEntries(searchProps);

			debug("Result count: " + results.size());
			for (Map.Entry<String, Destination> entry : results.entrySet()) {
				String name = entry.getKey();
				if (filter != null && filter.length() > 0 && !sortByDate) {
					if (filter.equals("0-9")) {
						char first = name.charAt(0);
						if( first < '0' || first > '9' )
							continue;
					}
					else if( ! name.toLowerCase(Locale.US).startsWith( filter.toLowerCase(Locale.US) ) ) {
						continue;
					}
				}
				if( search != null && search.length() > 0 ) {
					if( name.indexOf( search ) == -1 ) {
						continue;
					}
				}
				AddressBean bean = new AddressBean(name, entry.getValue());
				if (sortByDate) {
					Properties p = new Properties();
					Destination d = service.lookup(name, searchProps, p);
					if (d != null && !p.isEmpty())
						bean.setProperties(p);
				}
				list.addLast(bean);
			}
			AddressBean array[] = list.toArray(new AddressBean[list.size()]);
			if (sortByDate) {
				Arrays.sort(array, new AddressByDateSorter());
			} else if (!(results instanceof SortedMap)) {
				Arrays.sort(array, sorter);
			}
			entries = array;

			message = generateLoadMessage();
		}
		catch (RuntimeException e) {
			warn(e);
		}
		if( message.length() > 0 )
			message = "<p id=\"filtered\">" + message + "</p>";
		return message;
	}

	/** Perform actions, returning messages about this. */
	@Override
	public String getMessages()
	{
		if (isDirect())
			return super.getMessages();
		// Loading config and addressbook moved into getLoadBookMessages()
		String message = "";
		
		if( action != null ) {
			Properties nsOptions = new Properties();
			// only blockfile needs this
			nsOptions.setProperty("list", getFileName());
			if ("POST".equals(method) &&
                            (_context.getBooleanProperty(PROP_PW_ENABLE) ||
			     (serial != null && serial.equals(lastSerial)))) {
				boolean changed = false;
				if (action.equals(_t("Add")) || action.equals(_t("Replace")) || action.equals(_t("Add Alternate"))) {
					if(hostname != null && destination != null) {
						try {
							// throws IAE with translated message
							String host = AddressBean.toASCII(hostname);
							String displayHost = host.equals(hostname) ? hostname :
							                                             hostname + " (" + host + ')';

							Properties outProperties= new Properties();
							Destination oldDest = getNamingService().lookup(host, nsOptions, outProperties);
							if (oldDest != null && destination.equals(oldDest.toBase64())) {
								message = _t("Host name {0} is already in address book, unchanged.", displayHost);
							} else if (oldDest == null && action.equals(_t("Add Alternate"))) {
								message = _t("Host name {0} is not in  the address book.", displayHost);
							} else if (oldDest != null && action.equals(_t("Add"))) {
								message = _t("Host name {0} is already in address book with a different destination. Click \"Replace\" to overwrite.", displayHost);
							} else {
								boolean wasB32 = false;
								try {
									Destination dest;
									if (destination.length() >= 516) {
										dest = new Destination(destination);
									} else if (destination.contains(".b32.i2p")) {
										wasB32 = true;
										if (destination.startsWith("http://") ||
										    destination.startsWith("https://")) {
											// do them a favor, pull b32 out of pasted URL
											try {
												URI uri = new URI(destination);
												String b32 = uri.getHost();
												if (b32 == null || !b32.endsWith(".b32.i2p") || b32.length() < 60)
													throw new DataFormatException("");
												dest = _context.namingService().lookup(b32);
												if (dest == null)
													throw new DataFormatException(_t("Unable to resolve Base 32 address"));
											} catch(URISyntaxException use) {
												throw new DataFormatException("");
											}
										} else if (destination.endsWith(".b32.i2p") && destination.length() >= 60) {
											dest = _context.namingService().lookup(destination);
											if (dest == null)
												throw new DataFormatException(_t("Unable to resolve Base 32 address"));
										} else {
											throw new DataFormatException("");
										}
									} else {
										throw new DataFormatException("");
									}
									if (oldDest != null) {
										nsOptions.putAll(outProperties);
							                        String now = Long.toString(_context.clock().now());
										if (action.equals(_t("Add Alternate")))
							                        	nsOptions.setProperty("a", now);
										else
							                        	nsOptions.setProperty("m", now);
									}
						                        nsOptions.setProperty("s", _t("Manually added via SusiDNS"));
									boolean success;
							                if (action.equals(_t("Add Alternate"))) {
										// check all for dups
										List<Destination> all = getNamingService().lookupAll(host);
										if (all == null || !all.contains(dest)) {
											success = getNamingService().addDestination(host, dest, nsOptions);
										} else {
											// will get generic message below
											success = false;
										}
									} else {
										success = getNamingService().put(host, dest, nsOptions);
									}
									if (success) {
										changed = true;
										if (oldDest == null || action.equals(_t("Add Alternate")))
											message = _t("Destination added for {0}.", displayHost);
										else
											message = _t("Destination changed for {0}.", displayHost);
										if (!host.endsWith(".i2p"))
											message += "<br>" + _t("Warning - hostname does not end with \".i2p\"");
										// clear form
										hostname = null;
										destination = null;
									} else {
										message = _t("Failed to add Destination for {0} to naming service {1}", displayHost, getNamingService().getName()) + "<br>";
									}
								} catch (DataFormatException dfe) {
									String msg = dfe.getMessage();
									if (msg != null && msg.length() > 0)
										message = msg;
									else if (wasB32)
										message = _t("Invalid Base 32 hostname.");
									else
										message = _t("Invalid Base 64 destination.");
								}
							}
						} catch (IllegalArgumentException iae) {
							message = iae.getMessage();
							if (message == null)
								message = _t("Invalid hostname \"{0}\".", hostname);
						}
					} else {
						message = _t("Please enter a hostname and destination");
					}
					// clear search when adding
					search = null;
				} else if (action.equals(_t("Delete Selected")) || action.equals(_t("Delete Entry"))) {
					String name = null;
					int deleted = 0;
					Destination matchDest = null;
					if (action.equals(_t("Delete Entry"))) {
						// remove specified dest only in case there is more than one
						if (destination != null) {
							try {
								matchDest = new Destination(destination);
							} catch (DataFormatException dfe) {}
						}
					}
					for (String n : deletionMarks) {
						boolean success;
						if (matchDest != null)
							success = getNamingService().remove(n, matchDest, nsOptions);
						else
							success = getNamingService().remove(n, nsOptions);
						String uni = AddressBean.toUnicode(n);
						String displayHost = uni.equals(n) ? n :  uni + " (" + n + ')';
						if (!success) {
							message += _t("Failed to delete Destination for {0} from naming service {1}", displayHost, getNamingService().getName()) + "<br>";
						} else if (deleted++ == 0) {
							changed = true;
							name = displayHost;
						}
					}
					if( changed ) {
						if (deleted == 1)
							// parameter is a hostname
							message += _t("Destination {0} deleted.", name);
						else
							// parameter will always be >= 2
							message = ngettext("1 destination deleted.", "{0} destinations deleted.", deleted);
					} else {
						message = _t("No entries selected to delete.");
					}
					// clear search when deleting
					if (action.equals(_t("Delete Entry")))
						search = null;
				}
				if( changed ) {
					message += "<br>" + _t("Address book saved.");
				}
			}			
			else {
				message = _t("Invalid form submission, probably because you used the \"back\" or \"reload\" button on your browser. Please resubmit.")
                                          + ' ' +
                                          _t("If the problem persists, verify that you have cookies enabled in your browser.");
			}
		}
		
		action = null;
		
		if( message.length() > 0 )
			message = styleMessage(message);
		return message;
	}

	/**
	 *  @since 0.9.35
	 */
        public void saveNotes() {
		if (action == null || !action.equals(_t("Save Notes")) ||
		    destination == null || detail == null || isDirect() ||
		    serial == null || !serial.equals(lastSerial))
			return;
		Properties nsOptions = new Properties();
		List<Properties> propsList = new ArrayList<Properties>(4);
		nsOptions.setProperty("list", getFileName());
		List<Destination> dests = getNamingService().lookupAll(detail, nsOptions, propsList);
		if (dests == null)
			return;
		for (int i = 0; i < dests.size(); i++) {
			if (!dests.get(i).toBase64().equals(destination))
				continue;
			Properties props = propsList.get(i);
			if (notes != null && notes.length() > 0) {
				byte[] nbytes = DataHelper.getUTF8(notes);
	                        if (nbytes.length > 255) {
					// violently truncate, possibly splitting a char
					byte[] newbytes = new byte[255];
					System.arraycopy(nbytes, 0, newbytes, 0, 255);
					notes = DataHelper.getUTF8(newbytes);
					// drop replacement char or split pair
					int last = notes.length() - 1;
					char lastc = notes.charAt(last);
					if (lastc == (char) 0xfffd || Character.isHighSurrogate(lastc))
						notes = notes.substring(0, last);
				}
				props.setProperty("notes", notes);
			} else {
				// not working
				//props.remove("notes");
				props.setProperty("notes", "");
			}
			props.setProperty("list", getFileName());
                        String now = Long.toString(_context.clock().now());
                       	props.setProperty("m", now);
			if (dests.size() > 1) {
				// we don't have any API to update properties on a single dest
				// so remove and re-add
				getNamingService().remove(detail, dests.get(i), nsOptions);
				getNamingService().addDestination(detail, dests.get(i), props);
			} else {
				getNamingService().put(detail, dests.get(i), props);
			}
			return;
		}
	}

	public void setH(String h) {
		this.detail = DataHelper.stripHTML(h);
	}

	/**
	 *  @since 0.9.35
	 */
	public void setNofilter_notes(String n) {
		notes = n;
	}

	public AddressBean getLookup() {
		if (this.detail == null)
			return null;
		if (isDirect()) {
			// go to some trouble to make this work for the published addressbook
			this.filter = this.detail.substring(0, 1);
			this.search = this.detail;
			// we don't want the messages, we just want to populate entries
			super.getLoadBookMessages();
			for (int i = 0; i < this.entries.length; i++) {
				if (this.search.equals(this.entries[i].getName()))
					return this.entries[i];
			}
			return null;
		}
		Properties nsOptions = new Properties();
		Properties outProps = new Properties();
		nsOptions.setProperty("list", getFileName());
		Destination dest = getNamingService().lookup(this.detail, nsOptions, outProps);
		if (dest == null)
			return null;
		AddressBean rv = new AddressBean(this.detail, dest);
		rv.setProperties(outProps);
		return rv;
	}

	/**
	 *  @since 0.9.26
	 */
	public List<AddressBean> getLookupAll() {
		if (this.detail == null)
			return null;
		if (isDirect()) {
			// won't work for the published addressbook
			AddressBean ab = getLookup();
			if (ab != null)
				return Collections.singletonList(ab);
			return null;
		}
		Properties nsOptions = new Properties();
		List<Properties> propsList = new ArrayList<Properties>(4);
		nsOptions.setProperty("list", getFileName());
		List<Destination> dests = getNamingService().lookupAll(this.detail, nsOptions, propsList);
		if (dests == null)
			return null;
		List<AddressBean> rv = new ArrayList<AddressBean>(dests.size());
		for (int i = 0; i < dests.size(); i++) {
			AddressBean ab = new AddressBean(this.detail, dests.get(i));
			ab.setProperties(propsList.get(i));
			rv.add(ab);
		}
		return rv;
	}

	/**
	 *  @since 0.9.20
	 */
	public void export(Writer out) throws IOException {
		Properties searchProps = new Properties();
		// only blockfile needs this
		searchProps.setProperty("list", getFileName());
		if (filter != null) {
			String startsAt = filter.equals("0-9") ? "[0-9]" : filter;
			searchProps.setProperty("startsWith", startsAt);
		}
		if (search != null && search.length() > 0)
			searchProps.setProperty("search", search.toLowerCase(Locale.US));
		getNamingService().export(out, searchProps);
		// No post-filtering for hosts.txt naming services. It is what it is.
	}

	/**
	 *  @return messages about this action
	 *  @since 0.9.40
	 */
	public String importFile(RequestWrapper wrequest) throws IOException {
		String message = "";
		InputStream in = wrequest.getInputStream("file");
		OutputStream out = null;
		File tmp = null;
		SingleFileNamingService sfns = null;
		try {
			// non-null but zero bytes if no file entered, don't know why
			if (in == null || in.available() <= 0) {
				return styleMessage(_t("You must enter a file"));
			}
			// copy to temp file
			tmp = new File(_context.getTempDir(), "susidns-import-" + _context.random().nextLong() + ".txt");
			out = new FileOutputStream(tmp);
			DataHelper.copy(in, out);
                        in.close();
                        in = null;
                        out.close();
                        out = null;
			// new SingleFileNamingService
			sfns = new SingleFileNamingService(_context, tmp.getAbsolutePath());
			// getEntries, copy over
			Map<String, Destination> entries = sfns.getEntries();
			int count = entries.size();
			if (count <= 0) {
				return styleMessage(_t("No entries found in file"));
			} else {
				NamingService service = getNamingService();
				int added = 0, dup = 0;
				Properties nsOptions = new Properties();
				nsOptions.setProperty("list", getFileName());
	                        String now = Long.toString(_context.clock().now());
	                       	nsOptions.setProperty("m", now);
				String filename = wrequest.getFilename("file");
				if (filename != null)
					nsOptions.setProperty("s", _t("Imported from file {0}", filename));
				else
					nsOptions.setProperty("s", _t("Imported from file"));
				for (Map.Entry<String, Destination> e : entries.entrySet()) {
					String host = e.getKey();
					Destination dest = e.getValue();
					boolean ok = service.putIfAbsent(host, dest, nsOptions);
					if (ok)
						added++;
					else
						dup++;
				}
				StringBuilder buf = new StringBuilder(128);
				if (added > 0)
					buf.append(styleMessage(ngettext("Loaded {0} entry from file",
				                                         "Loaded {0} entries from file",
				                                         added)));
				if (dup > 0)
					buf.append(styleMessage(ngettext("Skipped {0} duplicate entry from file",
				                                         "Skipped {0} duplicate entries from file",
				                                         dup)));
				return buf.toString();
			}
		} catch (IOException ioe) {
			return styleMessage(_t("Import from file failed") + " - " + ioe);
		} finally {
			if (in != null)
				try { in.close(); } catch (IOException ioe) {}
			if (out != null)
				try { out.close(); } catch (IOException ioe) {}
			// shutdown SFNS
			if (sfns != null)
			    sfns.shutdown();
			if (tmp != null)
			    tmp.delete();
		}
	}

	/**
	 *  @since 0.9.40
	 */
	private static String styleMessage(String message) {
		return "<p class=\"messages\">" + message + "</p>";
	}
	
	/**
	 *  @since 0.9.34
	 */
	public boolean haveImagegen() {
		return _context.portMapper().isRegistered("imagegen");
	}
}
