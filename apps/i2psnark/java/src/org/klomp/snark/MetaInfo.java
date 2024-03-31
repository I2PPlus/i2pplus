/* MetaInfo - Holds all information gotten from a torrent file.
   Copyright (C) 2003 Mark J. Wielaard

   This file is part of Snark.

   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 2, or (at your option)
   any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.klomp.snark;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SHA1;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.InvalidBEncodingException;

/**
 * Note: this class is buggy, as it doesn't propogate custom meta fields into the bencoded
 * info data, and from there to the info_hash.  At the moment, though, it seems to work with
 * torrents created by I2P-BT, I2PRufus and Azureus.
 *
 */
public class MetaInfo
{
  private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(MetaInfo.class);
  private final String announce;
  private final byte[] info_hash;
  private final String name;
  private final String name_utf8;
  private final List<List<String>> files;
  private final List<List<String>> files_utf8;
  private final List<String> attributes;
  private final List<Long> lengths;
  private final int piece_length;
  private final byte[] piece_hashes;
  private final long length;
  private final int privateTorrent; // 0: not present; 1: = 1; -1: = 0
  private final List<List<String>> announce_list;
  private final String comment;
  private final String created_by;
  private final long creation_date;
  private final List<String> url_list;
  private Map<String, BEValue> infoMap;
  private int infoBytesLength;

  /**
   *  Called by Storage when creating a new torrent from local data
   *
   *  @param announce may be null
   *  @param files null for single-file torrent
   *  @param lengths null for single-file torrent
   *  @param announce_list may be null
   *  @param created_by may be null
   *  @param url_list may be null
   *  @param comment may be null
   */
  public MetaInfo(String announce, String name, String name_utf8, List<List<String>> files, List<Long> lengths,
           int piece_length, byte[] piece_hashes, long length, boolean privateTorrent,
           List<List<String>> announce_list, String created_by, List<String> url_list, String comment)
  {
    this.announce = announce;
    this.name = name;
    this.name_utf8 = name_utf8;
    this.files = files == null ? null : Collections.unmodifiableList(files);
    this.files_utf8 = null;
    this.lengths = lengths == null ? null : Collections.unmodifiableList(lengths);
    this.piece_length = piece_length;
    this.piece_hashes = piece_hashes;
    this.length = length;
    this.privateTorrent = privateTorrent ? 1 : 0;
    this.announce_list = announce_list;
    this.comment = comment;
    this.created_by = created_by;
    this.creation_date = 0;
//    this.creation_date = I2PAppContext.getGlobalContext().clock().now();
    this.url_list = url_list;

    // TODO BEP 52 hybrid torrent with piece layers, meta version and file tree
    this.attributes = null;

    // TODO if we add a parameter for other keys
    //if (other != null) {
    //    otherInfo = new HashMap(2);
    //    otherInfo.putAll(other);
    //}

    this.info_hash = calculateInfoHash();
    //infoMap = null;
  }

  /**
   *  Preserves privateTorrent int value, for main()
   *
   *  @since 0.9.62
   */
  public MetaInfo(String announce, String name, String name_utf8, List<List<String>> files, List<Long> lengths,
           int piece_length, byte[] piece_hashes, long length, int privateTorrent,
           List<List<String>> announce_list, String created_by, List<String> url_list, String comment)
  {
    this.announce = announce;
    this.name = name;
    this.name_utf8 = name_utf8;
    this.files = files == null ? null : Collections.unmodifiableList(files);
    this.files_utf8 = null;
    this.lengths = lengths == null ? null : Collections.unmodifiableList(lengths);
    this.piece_length = piece_length;
    this.piece_hashes = piece_hashes;
    this.length = length;
    this.privateTorrent = privateTorrent;
    this.announce_list = announce_list;
    this.comment = comment;
    this.created_by = created_by;
    this.creation_date = I2PAppContext.getGlobalContext().clock().now();
    this.url_list = url_list;
    this.attributes = null;
    this.info_hash = calculateInfoHash();
  }

  /**
   * Creates a new MetaInfo from the given InputStream. The
   * InputStream must start with a correctly bencoded dictionary
   * describing the torrent.
   * Caller must close the stream.
   */
  public MetaInfo(InputStream in) throws IOException
  {
    this(new BDecoder(in));
  }

  /**
   * Creates a new MetaInfo from the given BDecoder. The BDecoder
   * must have a complete dictionary describing the torrent.
   */
  private MetaInfo(BDecoder be) throws IOException
  {
    // Note that evaluation order matters here...
    this(be.bdecodeMap().getMap());
    byte[] origInfohash = be.get_special_map_digest();
    // shouldn't ever happen
    if (!DataHelper.eq(origInfohash, info_hash))
        throw new InvalidBEncodingException("Infohash mismatch, please report");
  }

  /**
   * Creates a new MetaInfo from a Map of BEValues and the SHA1 over
   * the original bencoded info dictionary (this is a hack, we could
   * reconstruct the bencoded stream and recalculate the hash). Will
   * NOT throw a InvalidBEncodingException if the given map does not
   * contain a valid announce string.
   * WILL throw a InvalidBEncodingException if the given map does not
   * contain a valid info dictionary.
   */
  public MetaInfo(Map<String, BEValue> m) throws InvalidBEncodingException
  {
    if (_log.shouldDebug())
        _log.debug("Creating a metaInfo: " + m, new Exception("source"));
    BEValue val = m.get("announce");
    // Disabled check, we can get info from a magnet now
    if (val == null) {
        //throw new InvalidBEncodingException("Missing announce string");
        this.announce = null;
    } else {
        this.announce = val.getString();
    }

    // BEP 12
    val = m.get("announce-list");
    if (val == null) {
        this.announce_list = null;
    } else {
        this.announce_list = new ArrayList<List<String>>();
        List<BEValue> bl1 = val.getList();
        for (BEValue bev : bl1) {
            List<BEValue> bl2 = bev.getList();
            List<String> sl2 = new ArrayList<String>();
            for (BEValue bev2 : bl2) {
                sl2.add(bev2.getString());
            }
            this.announce_list.add(sl2);
        }
    }

    // BEP 19
    val = m.get("url-list");
    if (val == null) {
        this.url_list = null;
    } else {
        List<String> urllist;
        try {
            List<BEValue> bl1 = val.getList();
            urllist = new ArrayList<String>(bl1.size());
            for (BEValue bev : bl1) {
                urllist.add(bev.getString());
            }
        } catch (InvalidBEncodingException ibee) {
            // BEP 19 says it's a list but the example there
            // is for a single byte string, and we've seen this
            // in the wild.
            urllist = Collections.singletonList(val.getString());
        }
        this.url_list = urllist;
    }

    // misc. optional  top-level stuff
    val = m.get("comment");
    String st = null;
    if (val != null) {
        try {
            st = val.getString();
        } catch (InvalidBEncodingException ibee) {}
    }
    this.comment = st;
    val = m.get("created by");
    st = null;
    if (val != null) {
        try {
            st = val.getString();
        } catch (InvalidBEncodingException ibee) {}
    }
    this.created_by = st;
    val = m.get("creation date");
    long time = 0;
    if (val != null) {
        try {
            time = val.getLong() * 1000;
        } catch (InvalidBEncodingException ibee) {}
    }
    this.creation_date = time;

    val = m.get("info");
    if (val == null)
        throw new InvalidBEncodingException("Missing info map");
    Map<String, BEValue> info = val.getMap();
    infoMap = Collections.unmodifiableMap(info);

    val = info.get("name");
    if (val == null)
        throw new InvalidBEncodingException("Missing name string");
    name = val.getString();
    // We could silently replace the '/', but that messes up the info hash, so just throw instead.
    if (name.indexOf('/') >= 0)
        throw new InvalidBEncodingException("Invalid name containing '/' " + name);

    val = info.get("name.utf-8");
    if (val != null)
        name_utf8 = val.getString();
    else
        name_utf8 = null;

    // BEP 27
    val = info.get("private");
    if (val != null) {
        Object o = val.getValue();
        // Is it supposed to be a number or a string?
        // i2psnark does it as a string. BEP 27 doesn't say.
        // Transmission does numbers. So does libtorrent.
        // We handle both as of 0.9.9.
        // We switch to storing as number as of 0.9.14.
        boolean privat = "1".equals(o) || ((o instanceof Number) && ((Number) o).intValue() == 1);
        privateTorrent = privat ? 1 : -1;
    } else {
        privateTorrent = 0;
    }

    val = info.get("piece length");
    if (val == null)
        throw new InvalidBEncodingException("Missing piece length number");
    piece_length = val.getInt();

    val = info.get("pieces");
    if (val == null) {
        // BEP 52
        // We do the check here because a torrent file could be combined v1/v2,
        // so a version 2 value isn't by itself fatal
        val = info.get("meta version");
        if (val != null) {
            int version = val.getInt();
            if (version != 1)
                throw new InvalidBEncodingException("Version " + version + " torrent file not supported");
        }
        throw new InvalidBEncodingException("Missing piece bytes");
    }
    piece_hashes = val.getBytes();

    val = info.get("length");
    if (val != null)
      {
        // Single file case.
        length = val.getLong();
        files = null;
        files_utf8 = null;
        lengths = null;
        attributes = null;
      }
    else
      {
        // Multi file case.
        val = info.get("files");
        if (val == null)
          throw new InvalidBEncodingException
            ("Missing length number and/or files list");

        List<BEValue> list = val.getList();
        int size = list.size();
        if (size == 0)
          throw new InvalidBEncodingException("zero size files list");

        List<List<String>> m_files = new ArrayList<List<String>>(size);
        List<List<String>> m_files_utf8 = null;
        List<Long> m_lengths = new ArrayList<Long>(size);
        List<String> m_attributes = null;
        long l = 0;
        for (int i = 0; i < list.size(); i++)
          {
            Map<String, BEValue> desc = list.get(i).getMap();
            val = desc.get("length");
            if (val == null)
              throw new InvalidBEncodingException("Missing length number");
            long len = val.getLong();
            if (len < 0)
              throw new InvalidBEncodingException("Negative file length");
            m_lengths.add(Long.valueOf(len));
            // check for overflowing the long
            long oldTotal = l;
            l += len;
            if (l < oldTotal)
              throw new InvalidBEncodingException("Huge total length");

            val = desc.get("path");
            if (val == null)
              throw new InvalidBEncodingException("Missing path list");
            List<BEValue> path_list = val.getList();
            int path_length = path_list.size();
            if (path_length == 0)
              throw new InvalidBEncodingException("zero size file path list");

            List<String> file = new ArrayList<String>(path_length);
            Iterator<BEValue> it = path_list.iterator();
            while (it.hasNext()) {
                String s = it.next().getString();
                // We could throw an IBEE, but just silently replace instead.
                if (s.indexOf('/') >= 0)
                    s = s.replace("/", "_");
                file.add(s);
            }

            // quick dup check - case sensitive, etc. - Storage does a better job
            for (int j = 0; j < i; j++) {
                if (file.equals(m_files.get(j)))
                    throw new InvalidBEncodingException("Duplicate file path " + DataHelper.toString(file));
            }

            m_files.add(Collections.unmodifiableList(file));

            val = desc.get("path.utf-8");
            if (val != null) {
                m_files_utf8 = new ArrayList<List<String>>(size);
                path_list = val.getList();
                path_length = path_list.size();
                if (path_length > 0) {
                    file = new ArrayList<String>(path_length);
                    it = path_list.iterator();
                    while (it.hasNext())
                        file.add(it.next().getString());
                    m_files_utf8.add(Collections.unmodifiableList(file));
                }
            }

            // BEP 47
            val = desc.get("attr");
            if (val != null) {
                String s = val.getString();
                if (m_attributes == null) {
                    m_attributes = new ArrayList<String>(size);
                    for (int j = 0; j < i; j++) {
                        m_attributes.add("");
                    }
                    m_attributes.add(s);
                }
            } else {
                if (m_attributes != null)
                    m_attributes.add("");
            }
          }
        files = Collections.unmodifiableList(m_files);
        files_utf8 = m_files_utf8 != null ? Collections.unmodifiableList(m_files_utf8) : null;
        lengths = Collections.unmodifiableList(m_lengths);
        length = l;
        attributes = m_attributes;
      }

    info_hash = calculateInfoHash();
  }

  /**
   * Efficiently returns the name and the 20 byte SHA1 hash of the info dictionary in a torrent file
   * Caller must close stream.
   *
   * @param infoHashOut 20-byte out parameter
   * @since 0.8.5
   */
  public static String getNameAndInfoHash(InputStream in, byte[] infoHashOut) throws IOException {
      BDecoder bd = new BDecoder(in);
      Map<String, BEValue> m = bd.bdecodeMap().getMap();
      BEValue ibev = m.get("info");
      if (ibev == null)
          throw new InvalidBEncodingException("Missing info map");
      Map<String, BEValue> i = ibev.getMap();
      BEValue rvbev = i.get("name");
      if (rvbev == null)
          throw new InvalidBEncodingException("Missing name");
      byte[] h = bd.get_special_map_digest();
      System.arraycopy(h, 0, infoHashOut, 0, 20);
      return rvbev.getString();
  }

  /**
   * Returns the string representing the URL of the tracker for this torrent.
   * @return may be null!
   */
  public String getAnnounce()
  {
    return announce;
  }

  /**
   * Returns a list of lists of urls.
   *
   * @since 0.9.5
   */
  public List<List<String>> getAnnounceList() {
    return announce_list;
  }

  /**
   * Returns a list of urls or null.
   *
   * @since 0.9.48
   */
  public List<String> getWebSeedURLs() {
    return url_list;
  }

  /**
   * Returns the original 20 byte SHA1 hash over the bencoded info map.
   */
  public byte[] getInfoHash()
  {
    // XXX - Should we return a clone, just to be sure?
    return info_hash;
  }

  /**
   *  Returns the piece hashes.
   *
   *  @return not a copy, do not modify
   *  @since public since 0.9.53, was package private
   */
  public byte[] getPieceHashes()
  {
    return piece_hashes;
  }

  /**
   * Returns the requested name for the file or toplevel directory.
   * If it is a toplevel directory name getFiles() will return a
   * non-null List of file name hierarchy name.
   */
  public String getName()
  {
    return name;
  }

  /**
   * Is it a private torrent?
   * @since 0.9
   */
  public boolean isPrivate() {
    return privateTorrent > 0;
  }

  /**
   * @return 0 (default), 1 (set to 1), -1 (set to 0)
   * @since 0.9.62
   */
  public int getPrivateTrackerStatus() {
     return privateTorrent;
  }

  /**
   * Returns a list of lists of file name hierarchies or null if it is
   * a single name. It has the same size as the list returned by
   * getLengths().
   */
  public List<List<String>> getFiles()
  {
    return files;
  }

  /**
   * Is this file a padding file?
   * @since 0.9.48
   */
  public boolean isPaddingFile(int filenum) {
      if (attributes == null)
          return false;
      return attributes.get(filenum).indexOf('p') >= 0;
  }

  /**
   * Returns a list of Longs indication the size of the individual
   * files, or null if it is a single file. It has the same size as
   * the list returned by getFiles().
   */
  public List<Long> getLengths()
  {
    return lengths;
  }

  /**
   * The comment string or null.
   * Not available for locally-created torrents.
   * @since 0.9.7
   */
  public String getComment() {
      return this.comment;
  }

  /**
   * The created-by string or null.
   * Not available for locally-created torrents.
   * @since 0.9.7
   */
  public String getCreatedBy() {
      return this.created_by;
  }

  /**
   * The creation date (ms) or zero.
   * As of 0.9.19, available for locally-created torrents.
   * @since 0.9.7
   */
  public long getCreationDate() {
      return this.creation_date;
  }

  /**
   * Returns the number of pieces.
   */
  public int getPieces()
  {
    return piece_hashes.length/20;
  }

  /**
   * Return the length of a piece. All pieces are of equal length
   * except for the last one (<code>getPieces()-1</code>).
   *
   * @throws IndexOutOfBoundsException when piece is equal to or
   * greater then the number of pieces in the torrent.
   */
  public int getPieceLength(int piece)
  {
    int pieces = getPieces();
    if (piece >= 0 && piece < pieces -1)
      return piece_length;
    else if (piece == pieces -1)
      return (int)(length - ((long)piece * piece_length));
    else
      throw new IndexOutOfBoundsException("no piece: " + piece);
  }

  /**
   * Checks that the given piece has the same SHA1 hash as the given
   * byte array. Returns random results or IndexOutOfBoundsExceptions
   * when the piece number is unknown.
   */
  public boolean checkPiece(int piece, byte[] bs, int off, int length)
  {
    //if (true)
        return fast_checkPiece(piece, bs, off, length);
    //else
    //    return orig_checkPiece(piece, bs, off, length);
  }

/****
  private boolean orig_checkPiece(int piece, byte[] bs, int off, int length) {
    // Check digest
    MessageDigest sha1;
    try
      {
        sha1 = MessageDigest.getInstance("SHA");
      }
    catch (NoSuchAlgorithmException nsae)
      {
        throw new InternalError("No SHA digest available: " + nsae);
      }

    sha1.update(bs, off, length);
    byte[] hash = sha1.digest();
    for (int i = 0; i < 20; i++)
      if (hash[i] != piece_hashes[20 * piece + i])
        return false;
    return true;
  }
****/

  private boolean fast_checkPiece(int piece, byte[] bs, int off, int length) {
    MessageDigest sha1 = SHA1.getInstance();

    sha1.update(bs, off, length);
    byte[] hash = sha1.digest();
    for (int i = 0; i < 20; i++) {
      if (hash[i] != piece_hashes[20 * piece + i])
        return false;
    }
    return true;
  }

  /**
   *  @return good
   *  @since 0.9.1
   */
  boolean checkPiece(PartialPiece pp) {
    int piece = pp.getPiece();
    byte[] hash;
    try {
        hash = pp.getHash();
    } catch (IOException ioe) {
        // Could be caused by closing a peer connnection
        // we don't want the exception to propagate through
        // to Storage.putPiece()
//        _log.warn("Error checking piece " + piece + " for " + name, ioe);

    if (_log.shouldDebug())
        _log.debug("Error checking piece [" + piece + "] for " + name);
        return false;
    }
    for (int i = 0; i < 20; i++) {
      if (hash[i] != piece_hashes[20 * piece + i])
        return false;
    }
    return true;
  }

  /**
   * Returns the total length of the torrent in bytes.
   * This includes any padding files.
   */
  public long getTotalLength()
  {
    return length;
  }

    @Override
  public String toString()
  {
    return "MetaInfo for: " + name
      + "\n* InfoHash: " + I2PSnarkUtil.toHex(info_hash)
      + "\n* Announce: " + announce
      + "\n* Size: " + length + " bytes, "
      + "Files: " + files + ", "
      + "Pieces: " + piece_hashes.length/20 + ", "
      + "Piece Length: " + piece_length + " bytes";
  }

  /**
   * Creates a copy of this MetaInfo that shares everything except the
   * announce URL.
   * Drops any announce-list.
   * Preserves infohash and info map, including any non-standard fields.
   * @param announce may be null
   */
  public MetaInfo reannounce(String announce) throws InvalidBEncodingException
  {
        Map<String, BEValue> m = new HashMap<String, BEValue>();
        if (announce != null)
            m.put("announce", new BEValue(DataHelper.getUTF8(announce)));
        Map<String, BEValue> info = createInfoMap();
        m.put("info", new BEValue(info));
        return new MetaInfo(m);
  }

  /**
   *  Called by servlet to save a new torrent file generated from local data
   */
  public synchronized byte[] getTorrentData()
  {
        Map<String, Object> m = new HashMap<String, Object>();
        if (announce != null)
            m.put("announce", announce);
        if (announce_list != null)
            m.put("announce-list", announce_list);
        // misc. optional  top-level stuff
        if (url_list != null)
            m.put("url-list", url_list);
        if (comment != null)
            m.put("comment", comment);
        if (created_by != null)
            m.put("created by", created_by);
        if (creation_date != 0)
            m.put("creation date", creation_date / 1000);

        Map<String, BEValue> info = createInfoMap();
        m.put("info", info);
        // don't save this locally, we should only do this once
        return BEncoder.bencode(m);
  }

  /**
   *  Side effect: Caches infoBytesLength.
   *  @since 0.8.4
   */
  public synchronized byte[] getInfoBytes() {
    if (infoMap == null)
        createInfoMap();
    byte[] rv = BEncoder.bencode(infoMap);
    infoBytesLength = rv.length;
    return rv;
  }

  /**
   *  The size of getInfoBytes().
   *  Cached.
   *  @since 0.9.48
   */
  public synchronized int getInfoBytesLength() {
    if (infoBytesLength > 0)
        return infoBytesLength;
    return getInfoBytes().length;
  }

  /** @return an unmodifiable view of the Map */
  private Map<String, BEValue> createInfoMap()
  {
    // If we loaded this metainfo from a file, we have the map, and we must use it
    // or else we will lose any non-standard keys and corrupt the infohash.
    if (infoMap != null)
        return Collections.unmodifiableMap(infoMap);
    // we should only get here if serving a magnet on a torrent we created
    // or on edit torrent save
    if (_log.shouldDebug())
        _log.debug("Creating new infomap", new Exception());
    // otherwise we must create it
    Map<String, BEValue> info = new HashMap<String, BEValue>();
    info.put("name", new BEValue(DataHelper.getUTF8(name)));
    if (name_utf8 != null)
        info.put("name.utf-8", new BEValue(DataHelper.getUTF8(name_utf8)));
    // BEP 27
    if (privateTorrent != 0)
        // switched to number in 0.9.14
        //info.put("private", new BEValue(DataHelper.getUTF8("1")));
        info.put("private", new BEValue(Integer.valueOf(privateTorrent > 0 ? 1 : 0)));

    info.put("piece length", new BEValue(Integer.valueOf(piece_length)));
    info.put("pieces", new BEValue(piece_hashes));
    if (files == null)
      info.put("length", new BEValue(Long.valueOf(length)));
    else
      {
        List<BEValue> l = new ArrayList<BEValue>();
        for (int i = 0; i < files.size(); i++)
          {
            Map<String, BEValue> file = new HashMap<String, BEValue>();
            List<String> fi = files.get(i);
            List<BEValue> befiles = new ArrayList<BEValue>(fi.size());
            for (int j = 0; j < fi.size(); j++) {
                befiles.add(new BEValue(DataHelper.getUTF8(fi.get(j))));
            }
            file.put("path", new BEValue(befiles));
            if ( (files_utf8 != null) && (files_utf8.size() > i) ) {
                List<String> fiu = files_utf8.get(i);
                List<BEValue> beufiles = new ArrayList<BEValue>(fiu.size());
                for (int j = 0; j < fiu.size(); j++) {
                    beufiles.add(new BEValue(DataHelper.getUTF8(fiu.get(j))));
                }
                file.put("path.utf-8", new BEValue(beufiles));
            }
            file.put("length", new BEValue(lengths.get(i)));
            String attr = null;
            if (attributes != null) {
                attr = attributes.get(i);
                if (attr.length() > 0)
                    file.put("attr", new BEValue(DataHelper.getASCII(attr)));
            }
            l.add(new BEValue(file));
          }
        info.put("files", new BEValue(l));
      }

    // TODO BEP 52 meta version and file tree

    // TODO if we add the ability for other keys in the first constructor
    //if (otherInfo != null)
    //    info.putAll(otherInfo);

    infoMap = info;
    return Collections.unmodifiableMap(infoMap);
  }

  private byte[] calculateInfoHash()
  {
    Map<String, BEValue> info = createInfoMap();
    if (_log.shouldDebug()) {
        StringBuilder buf = new StringBuilder(128);
        buf.append("info: ");
        for (Map.Entry<String, BEValue> entry : info.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            buf.append(key).append('=');
            buf.append(val.toString());
        }
        _log.debug(buf.toString());
    }
    byte[] infoBytes = BEncoder.bencode(info);
    //_log.debug("info bencoded: [" + Base64.encode(infoBytes, true) + "]");
        MessageDigest digest = SHA1.getInstance();
        byte hash[] = digest.digest(infoBytes);
        if (_log.shouldDebug())
            _log.debug("[InfoHash " + I2PSnarkUtil.toHex(hash) + "]");
        return hash;
  }

  /** @since 0.8.5 */
  public static void main(String[] args) {
      boolean error = false;
      String created_by = null;
      String announce = null;
      List<String> url_list = null;
      String comment = null;
      Getopt g = new Getopt("Storage", args, "a:c:m:w:");
      try {
          int c;
          while ((c = g.getopt()) != -1) {
            switch (c) {
              case 'a':
                  announce = g.getOptarg();
                  break;

              case 'c':
                  created_by = g.getOptarg();
                  break;

              case 'm':
                  comment = g.getOptarg();
                  break;

              case 'w':
                  if (url_list == null)
                      url_list = new ArrayList<String>();
                  url_list.add(g.getOptarg());
                  break;

              case '?':
              case ':':
              default:
                  error = true;
                  break;
            }  // switch
          } // while
      } catch (RuntimeException e) {
          e.printStackTrace();
          error = true;
      }
      if (error || args.length - g.getOptind() <= 0) {
          System.err.println("Usage: MetaInfo [-a announceURL] [-c created-by] [-m comment] [-w webseed-url]* file.torrent [file2.torrent...]");
          System.exit(1);
      }
      for (int i = g.getOptind(); i < args.length; i++) {
          InputStream in = null;
          java.io.OutputStream out = null;
          try {
              in = new FileInputStream(args[i]);
              MetaInfo meta = new MetaInfo(in);
              System.out.println(args[i] +
                                 "\nInfoHash:     " + I2PSnarkUtil.toHex(meta.getInfoHash()) +
                                 "\nAnnounce:     " + meta.getAnnounce() +
                                 "\nWebSeed URLs: " + meta.getWebSeedURLs() +
                                 "\nCreated By:   " + meta.getCreatedBy() +
                                 "\nComment:      " + meta.getComment());
              if (created_by != null || announce != null || url_list != null || comment != null) {
                  String cb = created_by != null ? created_by : meta.getCreatedBy();
                  String an = announce != null ? announce : meta.getAnnounce();
                  String cm = comment != null ? comment : meta.getComment();
                  List<String> urls = url_list != null ? url_list : meta.getWebSeedURLs();
                  // changes/adds creation date
                  MetaInfo meta2 = new MetaInfo(an, meta.getName(), null, meta.getFiles(), meta.getLengths(),
                                                meta.getPieceLength(0), meta.getPieceHashes(), meta.getTotalLength(),
                                                meta.getPrivateTrackerStatus(), meta.getAnnounceList(), cb, urls, cm);
                  java.io.File from = new java.io.File(args[i]);
                  java.io.File to = new java.io.File(args[i] + ".bak");
                  if (net.i2p.util.FileUtil.copy(from, to, true, false)) {
                      out = new java.io.FileOutputStream(from);
                      out.write(meta2.getTorrentData());
                      out.close();
                      System.out.println("Modified " + from + " and backed up old file to " + to);
                      System.out.println(args[i] +
                                         "\nInfoHash:     " + I2PSnarkUtil.toHex(meta2.getInfoHash()) +
                                         "\nAnnounce:     " + meta2.getAnnounce() +
                                         "\nWebSeed URLs: " + meta2.getWebSeedURLs() +
                                         "\nCreated By:   " + meta2.getCreatedBy() +
                                         "\nComment:      " + meta2.getComment());
                  } else {
                      System.out.println("Failed backup of " + from + " to " + to);
                  }
              }
          } catch (IOException ioe) {
              System.err.println("Error in file " + args[i] + ": " + ioe);
          } finally {
              try { if (in != null) in.close(); } catch (IOException ioe) {}
              try { if (out != null) out.close(); } catch (IOException ioe) {}
          }
      }
  }
}
