package i2p.susi.webmail;

import i2p.susi.util.Buffer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Represents a draft email message with attachments and Bcc support.
 *
 * Differences from Mail:
 * - Never multipart, body is always text/plain UTF-8
 * - Attachments are just headers containing name, type, encoding, and path to file
 * - Bcc is a header
 *
 * @since 0.9.35
 */
class Draft extends Mail {

	/** Files attached to the draft. */
	private final List<Attachment> attachments;
	/**
	 * Directory the attachment paths of this draft are confined to, or null to
	 * accept the path as written.
	 */
	private final File attachmentDir;
	/** Blind copies, addresses only, enclosed by angle brackets. */
	private String[] bcc;        // addresses only, enclosed by <>
	/** Size of the draft's contents, in bytes. */
	private long size;
	/** Header marking an attachment in a sent message. */
	private static final String HDR_ATTACH = "X-I2P-Attachment: ";
	/**
	 * Header line carrying the blind-copy recipients of a draft, which a draft
	 * otherwise has no way to express in the body.
	 */
	public static final String HDR_BCC = "Bcc: ";

	/**
	 * Start an empty draft with no attachments, and no directory to confine an
	 * attachment path to. Only safe where the body is written, not read back:
	 * see the two-argument form for what a stored draft needs.
	 *
	 * @param uidl unique ID for this draft
	 */
	public Draft(String uidl) {
		this(uidl, null);
	}

	/**
	 * Start an empty draft with no attachments.
	 *
	 * @param uidl unique ID for this draft
	 * @param attachmentDir the directory an attachment file of this account must
	 *                      live in, or null to accept the path as written
	 * @since 0.9.72+
	 */
	public Draft(String uidl, File attachmentDir) {
		super(uidl);
		attachments = new ArrayList<>(4);
		this.attachmentDir = attachmentDir;
	}

	/**
	 * Overridden to process attachment and Bcc headers. The attachment headers
	 * are written before the identity headers, so a header that is neither is
	 * skipped, not taken as the end of the block, and both prefixes are matched
	 * without regard to case. A body is parsed more than once over a draft's
	 * life, so what the last parse found is dropped first: otherwise every
	 * re-parse adds the attachments again. An attachment named by a path outside
	 * the account's attachment directory is skipped, as
	 * {@link #attachmentFile(String, File)} decides.
	 */
	@Override
	public synchronized void setBody(Buffer rb) {
		super.setBody(rb);
		attachments.clear();
		bcc = null;
		size = rb.getLength();
		MailPart part = getPart();
		if (part != null) {
			String[] hdrs = part.headerLines;
			for (int i = 0; i < hdrs.length; i++) {
				String hdr = hdrs[i];
				if (hdr.regionMatches(true, 0, HDR_BCC, 0, HDR_BCC.length())) {
					ArrayList<String> list = new ArrayList<>();
					getRecipientsFromList(list, hdr.substring(HDR_BCC.length()).trim(), true);
					if (list.isEmpty()) {
						// don't set
					} else if (bcc == null) {
						bcc = list.toArray(new String[list.size()]);
					} else {
						// a second Bcc line, keep what the first one named
						for (int j = 0; j < bcc.length; j++) {
							list.add(j, bcc[j]);
						}
						bcc = list.toArray(new String[list.size()]);
					}
					continue;
				}
				if (!hdr.regionMatches(true, 0, HDR_ATTACH, 0, HDR_ATTACH.length()))
					continue;
				String[] flds = DataHelper.split(hdr.substring(HDR_ATTACH.length()), ",", 4);
				if (flds.length != 4)
					continue;
				byte[] b = Base64.decode(flds[0]);
				if (b == null)
					continue;
				String name = DataHelper.getUTF8(b);
				String type = flds[1];
				String enc = flds[2];
				b = Base64.decode(flds[3]);
				if (b == null)
					continue;
				String path = DataHelper.getUTF8(b);
				File f = attachmentFile(path, attachmentDir);
				if (f == null) {
					Log log = I2PAppContext.getGlobalContext().logManager().getLog(Draft.class);
					if (log.shouldWarn())
						log.warn("Draft attachment skipped -> Not an upload of this account: " + path);
					continue;
				}
				Attachment a = new Attachment(name, type, enc, f);
				size += a.getSize();
				attachments.add(a);
			}
		}
	}

	/**
	 * The file a draft's attachment header names, if it really is one of this
	 * account's uploads.
	 *
	 * A draft stores an attachment as a path written into its own body, and that
	 * body can arrive from a restored backup or from the import/ directory, not
	 * only from the upload that created it. Without this check the path is taken
	 * on trust and the file is opened wherever it points, so a draft can name any
	 * file the router's user can read. Confining it to the account's attachments
	 * directory, and rejecting a symlink there, keeps the path a name for an
	 * upload and nothing more.
	 *
	 * @param path the path as the draft recorded it
	 * @param dir the directory uploads are written to, null to accept any path
	 * @return the file to open, null if the path is not inside dir or is a link
	 * @since 0.9.72+
	 */
	static File attachmentFile(String path, File dir) {
		if (path == null || path.isEmpty())
			return null;
		if (dir == null)
			return new File(path);
		File f = new File(path);
		if (Files.isSymbolicLink(f.toPath()))
			return null;
		try {
			String parent = dir.getCanonicalPath();
			String child = f.getCanonicalPath();
			if (!child.startsWith(parent + File.separator))
				return null;
		} catch (IOException ioe) {
			return null;
		}
		return f;
	}

	/**
	 * Includes size of attachments.
	 *
	 * @return body plus attachment size in bytes
	 * @since 0.9.62
	 */
	@Override
	public synchronized long getSize() {
		return size;
	}

	/**
	 * Does nothing: a draft computes its own size from the body and its
	 * attachments, so there is no server-reported size to record.
	 *
	 * @since 0.9.62
	 */
	@Override
	public synchronized void setSize(long size) { /* no-op */ }

	/**
	 * Is any file attached to this draft?
	 *
	 * @return whether attachment is present
	 */
	@Override
	public synchronized boolean hasAttachment() {
		return !attachments.isEmpty();
	}

	/**
	 * The blind-copy recipients found in the draft's Bcc header, with no
	 * header meaning none were entered.
	 *
	 * @return may be null
	 */
	public synchronized String[] getBcc() {
		return bcc;
	}

	/**
	 * The live attachment list, so callers can add or drop entries directly.
	 *
	 * @return non-null, not a copy
	 */
	public synchronized List<Attachment> getAttachments() {
		return attachments;
	}

	/**
	 * Adds an attachment if not already present.
	 *
	 * @param a the attachment to add
	 * @return the index of the attachment
	 */
	public synchronized int addAttachment(Attachment a) {
		int rv = attachments.indexOf(a);
		if (rv >= 0)
			return rv;
		rv = attachments.size();
		attachments.add(a);
		size += a.getSize();
		return rv;
	}

	/**
	 * Removes an attachment by index and deletes its data file.
	 *
	 * @param index the 0-based position in the attachment list; out of range is ignored
	 */
	public synchronized void removeAttachment(int index) {
		if (index >= 0 && index < attachments.size()) {
			Attachment a = attachments.get(index);
			size -= a.getSize();
			a.deleteData();
			attachments.remove(index);
		}
	}

	/**
	 * Removes all attachments and deletes their data files.
	 */
	public synchronized void clearAttachments() {
		for (Attachment a : attachments) {
			size -= a.getSize();
			a.deleteData();
		}
		attachments.clear();
	}

	/**
	 * Encodes all attachments as header lines for storage.
	 *
	 * @return the encoded attachment headers
	 */
	public synchronized StringBuilder encodeAttachments() {
		StringBuilder buf = new StringBuilder(256 * attachments.size());
		if (attachments.isEmpty())
			return buf;
		for (int i = 0; i < attachments.size(); i++) {
			Attachment a = attachments.get(i);
			buf.append(HDR_ATTACH);
			buf.append(Base64.encode(a.getFileName())).append(',');
			buf.append(a.getContentType()).append(',');
			buf.append(a.getTransferEncoding()).append(',');
			buf.append(Base64.encode(a.getPath())).append("\r\n");
		}
		return buf;
	}
}
