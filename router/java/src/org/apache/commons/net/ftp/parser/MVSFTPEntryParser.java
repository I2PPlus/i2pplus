/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.net.ftp.parser;

import java.text.ParseException;
import java.util.List;

import org.apache.commons.net.ftp.Configurable;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPFileEntryParser;

/**
 * Implements {@link FTPFileEntryParser} and {@link Configurable} for IBM zOS/MVS Systems.
 *
 * @see FTPFileEntryParser Usage instructions.
 */
public class MVSFTPEntryParser extends ConfigurableFTPFileEntryParserImpl {

    static final int UNKNOWN_LIST_TYPE = -1;
    static final int FILE_LIST_TYPE = 0;
    static final int MEMBER_LIST_TYPE = 1;
    static final int UNIX_LIST_TYPE = 2;
    static final int JES_LEVEL_1_LIST_TYPE = 3;
    static final int JES_LEVEL_2_LIST_TYPE = 4;

    /**
     * Dates are ignored for file lists, but are used for member lists where possible
     */
    static final String DEFAULT_DATE_FORMAT = "yyyy/MM/dd HH:mm"; // 2001/09/18
                                                                  // 13:52

    /**
     * Matches these entries:
     *
     * <pre>
     *  Volume Unit    Referred Ext Used Recfm Lrecl BlkSz Dsorg Dsname
     *  B10142 3390   2006/03/20  2   31  F       80    80  PS   MDI.OKL.WORK
     * </pre>
     *
     * @see <a href= "https://www.ibm.com/support/knowledgecenter/zosbasics/com.ibm.zos.zconcepts/zconcepts_159.htm">Data set record formats</a>
     */
    static final String FILE_LIST_REGEX = "\\S+\\s+" + // volume
                                                       // ignored
            "\\S+\\s+" + // unit - ignored
            "\\S+\\s+" + // access date - ignored
            "\\S+\\s+" + // extents -ignored
            // If the values are too large, the fields may be merged (NET-639)
            "(?:\\S+\\s+)?" + // used - ignored
            "\\S+\\s+" + // recfm - ignored
            "\\S+\\s+" + // logical record length -ignored
            "\\S+\\s+" + // block size - ignored
            "(PS|PO|PO-E)\\s+" + // Dataset organization. Many exist
            // but only support: PS, PO, PO-E
            "(\\S+)\\s*"; // Dataset Name (file name)

    /**
     * Matches these entries:
     *
     * <pre>
     *   Name      VV.MM   Created       Changed      Size  Init   Mod   Id
     *   TBSHELF   01.03 2002/09/12 2002/10/11 09:37    11    11     0 KIL001
     * </pre>
     */
    static final String MEMBER_LIST_REGEX = "(\\S+)\\s+" + // name
            "\\S+\\s+" + // version, modification (ignored)
            "\\S+\\s+" + // create date (ignored)
            "(\\S+)\\s+" + // modification date
            "(\\S+)\\s+" + // modification time
            "\\S+\\s+" + // size in lines (ignored)
            "\\S+\\s+" + // size in lines at creation(ignored)
            "\\S+\\s+" + // lines modified (ignored)
            "\\S+\\s*"; // id of user who modified (ignored)

    /**
     * Matches these entries, note: no header:
     *
     * <pre>
     *   IBMUSER1  JOB01906  OUTPUT    3 Spool Files
     *   012345678901234567890123456789012345678901234
     *             1         2         3         4
     * </pre>
     */
    static final String JES_LEVEL_1_LIST_REGEX = "(\\S+)\\s+" + // job name ignored
            "(\\S+)\\s+" + // job number
            "(\\S+)\\s+" + // job status (OUTPUT,INPUT,ACTIVE)
            "(\\S+)\\s+" + // number of spool files
            "(\\S+)\\s+" + // Text "Spool" ignored
            "(\\S+)\\s*" // Text "Files" ignored
    ;

    /**
     * JES INTERFACE LEVEL 2 parser Matches these entries:
     *
     * <pre>
     * JOBNAME  JOBID    OWNER    STATUS CLASS
     * IBMUSER1 JOB01906 IBMUSER  OUTPUT A        RC=0000 3 spool files
     * IBMUSER  TSU01830 IBMUSER  OUTPUT TSU      ABEND=522 3 spool files
     * </pre>
     *
     * Sample output from FTP session:
     *
     * <pre>
     * ftp> quote site filetype=jes
     * 200 SITE command was accepted
     * ftp> ls
     * 200 Port request OK.
     * 125 List started OK for JESJOBNAME=IBMUSER*, JESSTATUS=ALL and JESOWNER=IBMUSER
     * JOBNAME  JOBID    OWNER    STATUS CLASS
     * IBMUSER1 JOB01906 IBMUSER  OUTPUT A        RC=0000 3 spool files
     * IBMUSER  TSU01830 IBMUSER  OUTPUT TSU      ABEND=522 3 spool files
     * 250 List completed successfully.
     * ftp> ls job01906
     * 200 Port request OK.
     * 125 List started OK for JESJOBNAME=IBMUSER*, JESSTATUS=ALL and JESOWNER=IBMUSER
     * JOBNAME  JOBID    OWNER    STATUS CLASS
     * IBMUSER1 JOB01906 IBMUSER  OUTPUT A        RC=0000
     * --------
     * ID  STEPNAME PROCSTEP C DDNAME   BYTE-COUNT
     * 001 JES2              A JESMSGLG       858
     * 002 JES2              A JESJCL         128
     * 003 JES2              A JESYSMSG       443
     * 3 spool files
     * 250 List completed successfully.
     * </pre>
     */

    static final String JES_LEVEL_2_LIST_REGEX = "(\\S+)\\s+" + // job name ignored
            "(\\S+)\\s+" + // job number
            "(\\S+)\\s+" + // owner ignored
            "(\\S+)\\s+" + // job status (OUTPUT,INPUT,ACTIVE) ignored
            "(\\S+)\\s+" + // job class ignored
            "(\\S+).*" // rest ignored
    ;

    private int isType = UNKNOWN_LIST_TYPE;

    /**
     * Fallback parser for Unix-style listings
     */
    private UnixFTPEntryParser unixFTPEntryParser;

    /*
     * --------------------------------------------------------------------- Very brief and incomplete description of the zOS/MVS-file system. (Note: "zOS" is
     * the operating system on the mainframe, and is the new name for MVS)
     *
     * The file system on the mainframe does not have hierarchical structure as for example the Unix file system. For a more comprehensive description,
     * please refer to the IBM manuals
     *
     * @LINK: https://publibfp.boulder.ibm.com/cgi-bin/bookmgr/BOOKS/dgt2d440/CONTENTS
     *
     *
     * Dataset names =============
     *
     * A dataset name consist of a number of qualifiers separated by '.', each qualifier can be at most 8 characters, and the total length of a dataset can be
     * max 44 characters including the dots.
     *
     *
     * Dataset organization ====================
     *
     * A dataset represents a piece of storage allocated on one or more disks. The structure of the storage is described with the field dataset organization
     * (DSORG). There are a number of dataset organizations, but only two are usable for FTP transfer.
     *
     * DSORG: PS: sequential, or flat file PO: partitioned dataset PO-E: extended partitioned dataset
     *
     * The PS file is just a flat file, as you would find it on the Unix file system.
     *
     * The PO and PO-E files, can be compared to a single level directory structure. A PO file consist of a number of dataset members, or files if you will. It
     * is possible to CD into the file, and to retrieve the individual members.
     *
     *
     * Dataset record format =====================
     *
     * The physical layout of the dataset is described on the dataset itself. There are a number of record formats (RECFM), but just a few is relevant for the
     * FTP transfer.
     *
     * Any one beginning with either F or V can safely be used by FTP transfer. All others should only be used with great care. F means a fixed number of
     * records per allocated storage, and V means a variable number of records.
     *
     *
     * Other notes ===========
     *
     * The file system supports automatically backup and retrieval of datasets. If a file is backed up, the ftp LIST command will return: ARCIVE Not Direct
     * Access Device KJ.IOP998.ERROR.PL.UNITTEST
     *
     *
     * Implementation notes ====================
     *
     * Only datasets that have dsorg PS, PO or PO-E and have recfm beginning with F or V or U, is fully parsed.
     *
     * The following fields in FTPFile is used: FTPFile.Rawlisting: Always set. FTPFile.Type: DIRECTORY_TYPE or FILE_TYPE or UNKNOWN FTPFile.Name: name
     * FTPFile.Timestamp: change time or null
     *
     *
     *
     * Additional information ======================
     *
     * The MVS ftp server supports a number of features via the FTP interface. The features are controlled with the FTP command quote site
     * filetype=<SEQ|JES|DB2> SEQ is the default and used for normal file transfer JES is used to interact with the Job Entry Subsystem (JES) similar to a job
     * scheduler DB2 is used to interact with a DB2 subsystem
     *
     * This parser supports SEQ and JES.
     */

    /**
     * The sole constructor for a MVSFTPEntryParser object.
     */
    public MVSFTPEntryParser() {
        super(""); // note the regex is set in preParse.
        super.configure(null); // configure parser with default configurations
    }

    @Override
    protected FTPClientConfig getDefaultConfiguration() {
        return new FTPClientConfig(FTPClientConfig.SYST_MVS, DEFAULT_DATE_FORMAT, null);
    }

    /**
     * Parses entries representing a dataset list.
     * <pre>
     * Format of ZOS/MVS file list: 1 2 3 4 5 6 7 8 9 10
     * Volume Unit Referred Ext Used Recfm Lrecl BlkSz Dsorg Dsname
     * B10142 3390 2006/03/20 2 31 F 80 80 PS MDI.OKL.WORK
     * ARCIVE Not Direct Access Device KJ.IOP998.ERROR.PL.UNITTEST
     * B1N231 3390 2006/03/20 1 15 VB 256 27998 PO PLU
     * B1N231 3390 2006/03/20 1 15 VB 256 27998 PO-E PLB
     * Migrated                                                HLQ.DATASET.NAME
     * </pre>
     * <pre>
     * ----------------------------------- Group within Regex [1] Volume [2] Unit [3] Referred [4] Ext: number of extents [5] Used [6] Recfm: Record format [7]
     * Lrecl: Logical record length [8] BlkSz: Block size [9] Dsorg: Dataset organization. Many exists but only support: PS, PO, PO-E [10] Dsname: Dataset name
     * </pre>
     *
     * @param entry zosDirectoryEntry
     * @return null: entry was not parsed.
     */
    private FTPFile parseFileList(final String entry) {
        if (matches(entry)) {
            final FTPFile file = new FTPFile();
            file.setRawListing(entry);
            final String name = group(2);
            final String dsorg = group(1);
            file.setName(name);

            // DSORG
            if ("PS".equals(dsorg)) {
                file.setType(FTPFile.FILE_TYPE);
            } else if ("PO".equals(dsorg) || "PO-E".equals(dsorg)) {
                // regex already ruled out anything other than PO or PO-E
                file.setType(FTPFile.DIRECTORY_TYPE);
            } else {
                return null;
            }

            return file;
        }

        final boolean migrated = entry.startsWith("Migrated");
        if (migrated || entry.startsWith("ARCIVE")) {
            // Type of file is unknown for migrated datasets
            final FTPFile file = new FTPFile();
            file.setRawListing(entry);
            file.setType(FTPFile.UNKNOWN_TYPE);
            file.setName(entry.split("\\s+")[migrated ? 1 : 5]);
            return file;
        }

        return null;
    }

    /**
     * Parses a line of a z/OS - MVS FTP server file listing and converts it into a usable format in the form of an {@code FTPFile} instance. If the
     * file listing line doesn't describe a file, then {@code null} is returned. Otherwise, a {@code FTPFile} instance representing the file is
     * returned.
     *
     * @param entry A line of text from the file listing
     * @return An FTPFile instance corresponding to the supplied entry
     */
    @Override
    public FTPFile parseFTPEntry(final String entry) {
        switch (isType) {
        case FILE_LIST_TYPE:
            return parseFileList(entry);
        case MEMBER_LIST_TYPE:
            return parseMemberList(entry);
        case UNIX_LIST_TYPE:
            return unixFTPEntryParser.parseFTPEntry(entry);
        case JES_LEVEL_1_LIST_TYPE:
            return parseJeslevel1List(entry);
        case JES_LEVEL_2_LIST_TYPE:
            return parseJeslevel2List(entry);
        default:
            break;
        }

        return null;
    }

    /**
     * Matches these entries, note: no header:
     *
     * <pre>
     * [1]      [2]      [3]   [4] [5]
     * IBMUSER1 JOB01906 OUTPUT 3 Spool Files
     * 012345678901234567890123456789012345678901234
     *           1         2         3         4
     * -------------------------------------------
     * Group in regex
     * [1] Job name
     * [2] Job number
     * [3] Job status (INPUT,ACTIVE,OUTPUT)
     * [4] Number of sysout files
     * [5] The string "Spool Files"
     * </pre>
     *
     * @param entry zosDirectoryEntry
     * @return null: entry was not parsed.
     */
    private FTPFile parseJeslevel1List(final String entry) {
        return parseJeslevelList(entry, 3);
    }

    /**
     * Matches these entries:
     *
     * <pre>
     * [1]      [2]      [3]     [4]    [5]
     * JOBNAME  JOBID    OWNER   STATUS CLASS
     * IBMUSER1 JOB01906 IBMUSER OUTPUT A       RC=0000 3 spool files
     * IBMUSER  TSU01830 IBMUSER OUTPUT TSU     ABEND=522 3 spool files
     * 012345678901234567890123456789012345678901234
     *           1         2         3         4
     * -------------------------------------------
     * Group in regex
     * [1] Job name
     * [2] Job number
     * [3] Owner
     * [4] Job status (INPUT,ACTIVE,OUTPUT)
     * [5] Job Class
     * [6] The rest
     * </pre>
     *
     * @param entry zosDirectoryEntry
     * @return null: entry was not parsed.
     */
    private FTPFile parseJeslevel2List(final String entry) {
        return parseJeslevelList(entry, 4);
    }

    private FTPFile parseJeslevelList(final String entry, final int matchNum) {
        if (matches(entry)) {
            final FTPFile file = new FTPFile();
            if (group(matchNum).equalsIgnoreCase("OUTPUT")) {
                file.setRawListing(entry);
                final String name = group(2); /* Job Number, used by GET */
                file.setName(name);
                file.setType(FTPFile.FILE_TYPE);
                return file;
            }
        }
        return null;
    }

    /**
     * Parses entries within a partitioned dataset.
     *
     * Format of a memberlist within a PDS:
     *
     * <pre>
     *    0         1        2          3        4     5     6      7    8
     *   Name      VV.MM   Created       Changed      Size  Init   Mod   Id
     *   TBSHELF   01.03 2002/09/12 2002/10/11 09:37    11    11     0 KIL001
     *   TBTOOL    01.12 2002/09/12 2004/11/26 19:54    51    28     0 KIL001
     *
     * -------------------------------------------
     * [1] Name
     * [2] VV.MM: Version . modification
     * [3] Created: yyyy / MM / dd
     * [4,5] Changed: yyyy / MM / dd HH:mm
     * [6] Size: number of lines
     * [7] Init: number of lines when first created
     * [8] Mod: number of modified lines a last save
     * [9] Id: User id for last update
     * </pre>
     *
     * @param entry zosDirectoryEntry
     * @return null: entry was not parsed.
     */
    private FTPFile parseMemberList(final String entry) {
        final FTPFile file = new FTPFile();
        if (matches(entry)) {
            file.setRawListing(entry);
            final String name = group(1);
            final String datestr = group(2) + " " + group(3);
            file.setName(name);
            file.setType(FTPFile.FILE_TYPE);
            try {
                file.setTimestamp(super.parseTimestamp(datestr));
            } catch (final ParseException e) {
                // just ignore parsing errors.
                // TODO check this is ok
                // Drop thru to try simple parser
            }
            return file;
        }
        /*
         * Assigns the name to the first word of the entry. Only to be used from a safe context, for example from a memberlist, where the regex for some reason
         * fails. Then just assign the name field of FTPFile.
         */
        if (entry != null && !entry.trim().isEmpty()) {
            file.setRawListing(entry);
            final String name = entry.split(" ")[0];
            file.setName(name);
            file.setType(FTPFile.FILE_TYPE);
            return file;
        }
        return null;
    }

    /**
     * Pre-parses is called as part of the interface. Per definition, it is called before the parsing takes place. Three kinds of lists are recognized:
     * <ul>
     *     <li>z/OS-MVS File lists,</li>
     *     <li>z/OS-MVS Member lists,</li>
     *     <li>Unix file lists.</li>
     * </ul>
     * @since 2.0
     */
    @Override
    public List<String> preParse(final List<String> orig) {
        // simply remove the header line. Composite logic will take care of the
        // two different types of
        // list in short order.
        if (orig != null && !orig.isEmpty()) {
            final String header = orig.get(0);
            if (header.contains("Volume") && header.contains("Dsname")) {
                setType(FILE_LIST_TYPE);
                super.setRegex(FILE_LIST_REGEX);
            } else if (header.contains("Name") && header.contains("Id")) {
                setType(MEMBER_LIST_TYPE);
                super.setRegex(MEMBER_LIST_REGEX);
            } else if (header.startsWith("total")) {
                setType(UNIX_LIST_TYPE);
                unixFTPEntryParser = new UnixFTPEntryParser();
            } else if (header.indexOf("Spool Files") >= 30) {
                setType(JES_LEVEL_1_LIST_TYPE);
                super.setRegex(JES_LEVEL_1_LIST_REGEX);
            } else if (header.startsWith("JOBNAME") && header.indexOf("JOBID") > 8) { // header contains JOBNAME JOBID OWNER // STATUS CLASS
                setType(JES_LEVEL_2_LIST_TYPE);
                super.setRegex(JES_LEVEL_2_LIST_REGEX);
            } else {
                setType(UNKNOWN_LIST_TYPE);
            }
            if (isType != JES_LEVEL_1_LIST_TYPE) { // remove header is necessary
                orig.remove(0);
            }
        }
        return orig;
    }

    /**
     * Sets the type of listing being processed.
     *
     * @param type The listing type.
     */
    void setType(final int type) {
        isType = type;
    }

}
