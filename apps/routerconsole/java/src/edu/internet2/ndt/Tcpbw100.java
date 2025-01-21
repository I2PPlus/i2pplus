package edu.internet2.ndt;

/*
 Copyright 2003 University of Chicago.  All rights reserved.
 The Web100 Network Diagnostic Tool (NDT) is distributed subject to the following license conditions:
 SOFTWARE LICENSE AGREEMENT
 Software: Web100 Network Diagnostic Tool (NDT)

 1. The "Software", below, refers to the Web100 Network Diagnostic Tool (NDT) in either source code,
    or binary form and accompanying documentation). Each licensee is addressed as "you" or "Licensee."

 2. The copyright holder shown above hereby grants Licensee a royalty-free non-exclusive license,
    subject to the limitations stated herein and U.S. Government license rights.

 3. You may modify and make a copy or copies of the Software for use within your organization,
    if you meet the following conditions:
 a. Copies in source code must include the copyright notice and this Software License Agreement.
 b. Copies in binary form must include the copyright notice and this Software License Agreement
    in the documentation and/or other materials provided with the copy.

 4. You may make a copy, or modify a copy or copies of the Software or any portion of it, thus
    forming a work based on the Software, and distribute copies outside your organization,
    if you meet all of the following conditions:
 a. Copies in source code must include the copyright notice and this Software License Agreement;
 b. Copies in binary form must include the copyright notice and this Software License Agreement
    in the documentation and/or other materials provided with the copy;
 c. Modified copies and works based on the Software must carry prominent notices stating that you
    changed specified portions of the Software.

 5. Portions of the Software resulted from work developed under a U.S. Government contract and are
    subject to the following license: the Government is granted for itself and others acting on its
    behalf a paid-up, non-exclusive, irrevocable worldwide license in this computer software to
    reproduce, prepare derivative works, and perform publicly and display publicly.

 6. WARRANTY DISCLAIMER. THE SOFTWARE IS SUPPLIED "AS IS" WITHOUT WARRANTY OF ANY KIND.
    THE COPYRIGHT HOLDER,THE UNITED STATES, THE UNITED STATES DEPARTMENT OF ENERGY, AND THEIR
    EMPLOYEES: (1) DISCLAIM ANY WARRANTIES, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY
    IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, TITLE OR NON-INFRINGEMENT,
    (2) DO NOT ASSUME ANY LEGAL LIABILITY OR RESPONSIBILITY FOR THE ACCURACY, COMPLETENESS, OR
    USEFULNESS OF THE SOFTWARE, (3) DO NOT REPRESENT THAT USE OF THE SOFTWARE WOULD NOT INFRINGE
    PRIVATELY OWNED RIGHTS, (4) DO NOT WARRANT THAT THE SOFTWARE WILL FUNCTION UNINTERRUPTED, THAT IT
    IS ERROR-FREE OR THAT ANY ERRORS WILL BE CORRECTED.

 7. LIMITATION OF LIABILITY. IN NO EVENT WILL THE COPYRIGHT HOLDER, THE UNITED STATES, THE UNITED STATES
    DEPARTMENT OF ENERGY, OR THEIR EMPLOYEES: BE LIABLE FOR ANY INDIRECT, INCIDENTAL, CONSEQUENTIAL,
    SPECIAL OR PUNITIVE DAMAGES OF ANY KIND OR NATURE, INCLUDING BUT NOT LIMITED TO LOSS OF PROFITS OR
    LOSS OF DATA, FOR ANY REASON WHATSOEVER, WHETHER SUCH LIABILITY IS ASSERTED ON THE BASIS OF CONTRACT,
    TORT (INCLUDING NEGLIGENCE OR STRICT LIABILITY), OR OTHERWISE, EVEN IF ANY OF SAID PARTIES HAS BEEN
    WARNED OF THE POSSIBILITY OF SUCH LOSS OR DAMAGES.
    The Software was developed at least in part by the University of Chicago, as Operator of Argonne National
    Laboratory (http://miranda.ctd.anl.gov:7123/).
 */

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.StringTokenizer;

import com.vuze.plugins.mlab.tools.ndt.swingemu.*;

import net.i2p.I2PAppContext;
import net.i2p.util.Addresses;
import net.i2p.util.I2PAppThread;
import net.i2p.util.I2PSSLSocketFactory;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/*
 * Naming convention used: Hungarian, with the following details
 * _VarName: Instance variables
 * __Varname: Static variables (instead of c_VarName to reduce length)
 * iVarName: Integer variable
 * sVarName: String variable
 * bVarName: boolean variable
 * dVarName: double variable
 * _iaVarName: Integer "Array"  variable
 * ...and some other self descriptive examples are..
 * _rscBundleMessages : class scoped ResourceBundle Variable called "Messages"
 * _cmboboxIpV6 : Class scoped combo-box variable to indicate IpV6 choice..
 *
 * Some variables which were called "pub_xxx" are declared to have "accessor" methods for use by
 * other clients. I have left this untouched. These are private variables. Though the type is
 * not evident from the declaration immediately, the "getter/setter" methods for them will immediately
 * indicate their types
 */

/**
 * Main Applet class that creates UI, defines tests and interprets results from the tests.
 */
public class Tcpbw100 extends JApplet implements ActionListener {

    /**
     * Compiler generated UID that does not follow naming convention, and is not related to functionality itself
     */
    private static final long serialVersionUID = -2030725073538492542L;

    JTextArea _txtDiagnosis, _txtStatistics;
    ResultsTextPane _resultsTxtPane;
    String _sErrMsg;
    JButton _buttonStartTest;
    JButton _buttonDetails;
    JButton _buttonStatistics;
    JCheckBox _chkboxDefaultTest, _chkboxPreferIPv6;
    JSpinner _spinnerTestCount = new JSpinner();
    String[] _saDelays = { "immediate", "1min", "5mins", "10mins", "30mins", "2hours", "12hours", "1day" };

    boolean _bFailed;
    NewFrame _frameWeb100Vars, _frameDetailedStats, _frameOptions;
    double _dTime;
    int _s2cspdUpdateTime = 500, _c2sspdUpdateTime = 500; // ms
    int _iECNEnabled, _iNagleEnabled, MSSSent, MSSRcvd;
    int _iSACKEnabled, _iTimestampsEnabled, _iWinScaleRcvd, _iWinScaleSent;
    int _iFastRetran, _iAckPktsOut, _iSmoothedRTT, _iCurrentCwnd, _iMaxCwnd;
    int _iSndLimTimeRwin, _iSndLimTimeCwnd, _iSndLimTimeSender;
    int _iSndLimTransRwin, _iSndLimTransCwnd, _iSndLimTransSender, _iMaxSsthresh;
    int _iSumRTT, _iCountRTT, _iCurrentMSS, _iTimeouts, _iPktsRetrans;
    int _iSACKsRcvd, _iDupAcksIn, _iMaxRwinRcvd, _iMaxRwinSent;
    int _iDataPktsOut, _iRcvbuf, _iSndbuf, _iAckPktsIn;
    long _iDataBytesOut;
    int _iPktsOut, _iCongestionSignals, _iRcvWinScale;
    int _iPkts, _iLength = NDTConstants.PREDEFINED_BUFFER_SIZE, _iCurrentRTO;
    int _iC2sData, _iC2sAck, _iS2cData, _iS2cAck;
    String _sServerType = "web100"; // Lowercase string either web100 or web10g used to select Message based upon server type
    double _dS2cspd, _dC2sspd, _dSc2sspd, _dSs2cspd;
    int _iSsndqueue;
    double _dSbytes;
    byte[] _yabuff2Write;

    /*
     * Added by Martin Sandsmark, UNINETT AS Internationalization
     */
    private Locale _localeObj;
    private ResourceBundle _resBundDisplayMsgs;
    private String _sLang = "en";
    private String _sCountry = "US";
    private String _sClient = "applet";

    // These variables are self-explanatory. Do not follow naming convention, but left that way
    int half_duplex, congestion, bad_cable, mismatch;
    double mylink;
    double loss, estimate, avgrtt, spd, waitsec, timesec, rttsec;
    double order, rwintime, sendtime, cwndtime, rwin, swin, cwin;
    double aspd;
    // end naming convention-not-followed variables

    boolean _bIsApplication = false;
    private final AtomicBoolean _bTestInProgress = new AtomicBoolean();
    String sHostName = null;
    InetAddress hostAddress = null;
    String _sTestResults, _sMidBoxTestResult;
    byte _yTests = NDTConstants.TEST_C2S | NDTConstants.TEST_S2C | NDTConstants.TEST_STATUS | NDTConstants.TEST_META;
    int _iC2sSFWResult = NDTConstants.SFW_NOTTESTED;
    int _iS2cSFWResult = NDTConstants.SFW_NOTTESTED;

    /*
     * JavaScript access API extension added by Seth Peery and Gregory Wilson,
     * Virginia Tech October 28, 2009 This section adds classwide variables,
     * written to at runtime, which are then exposed by public accessor methods
     * that can be called from web applications using NDT as a back-end.
     *
     * These variables are accessed by the setter/getter methods.
     * While they do not follow naming convention, they are left this way.
     * pub_c2sspd is assigned the value of _dC2sspd (declared above).
     * The pub_xxx version seems to be used for making public to javascript.
     * No other details known.
     */
    private double pub_c2sspd = 0.0;
    private double pub_s2cspd = 0.0;
    private int pub_CurRwinRcvd = 0; // source variable does not exist
    private int pub_MaxRwinRcvd = 0;
    private int pub_MinRTT = 0; // source variable does not exist
    private int pub_MaxRTT = 0; // source variable does not exist
    private double pub_loss = 0.0;
    private double pub_avgrtt = 0.0;
    // TODO:Getter/setter commented out for both below. Why?
    private int pub_MinRTO = 0; // source variable does not exist
    private int pub_MaxRTO = 0; // source variable does not exist
    private int pub_CurRTO = 0;
    private int pub_SACKsRcvd = 0;
    private String pub_osVer = "unknown";
    private String pub_pluginVer = "unknown";
    private String pub_host = "unknown";
    private String pub_osName = "unknown";
    private String pub_osArch = "unknown";
    private int pub_mismatch = 0;
    private int pub_Bad_cable = 0;
    private int pub_congestion = 0;
    private double pub_cwndtime = 0.0;
    private double pub_pctRcvrLimited = 0.0;
    private String pub_AccessTech = "unknown";
    private String pub_natBox = "unknown";
    private int pub_DupAcksOut = 0;
    private int pub_DupAcksIn = 0;
    private Date pub_TimeStamp;
    private String pub_isReady = new String("no");
    private String pub_clientIP = "unknown";
    private int pub_jitter = 0; // unused. TODO: find out use
    private int pub_Timeouts = 0;
    private String pub_errmsg = "Test not run.";
    private String pub_diagnosis = "Test not run.";
    private String pub_statistics = "Test not run.";
    private String pub_status = "notStarted";
    private double pub_time = 0.0;
    private long pub_bytes = 0;
    private String _sIsAutoRun;
    private String _sUserAgent = null;
    private boolean jsonSupport = true;
    private boolean retry = false;

    // I2P
    private String _displayStatus = "";
    private final I2PAppContext _context = I2PAppContext.getGlobalContext();
    private final Log _log = _context.logManager().getLog(Tcpbw100.class);
    private final boolean _useSSL;
    private final I2PSSLSocketFactory _sslFactory;
    private StatusPanel _sPanel;

    public Tcpbw100(boolean useSSL) {
        super();
        I2PSSLSocketFactory sslFactory = null;
        if (useSSL) {
            try {sslFactory = new I2PSSLSocketFactory(_context, true, "certificates/ndt");}
            catch (GeneralSecurityException gse) {throw new IllegalStateException("init", gse);}
        }
        _sslFactory = sslFactory;
        _useSSL = useSSL;
    }

    /**
     * public static void main for invoking as an Application
     * @param args String array of command line arguments
     * @throws IllegalArgumentException on bad hostname
     */
    public static void main(String[] args) {
        Tcpbw100 test = mainSupport(args);
        test.runIt();
    }

    /**
     *  bigly
     * @throws IllegalArgumentException on bad hostname
     */
    public static Tcpbw100 mainSupport(String[] args) {
        JFrame frame = new JFrame("ANL/Internet2 NDT (applet)");
        boolean useSSL = args.length > 0 && args[0].equals("-s");
        if (useSSL) {args = Arrays.copyOfRange(args, 1, args.length);}
        if (args.length < 1 || args.length > 2) {
            System.out.println("Usage: java -jar Tcpbw100.jar [-s] <hostname> [client-id]");
            System.exit(0);
        }
        final Tcpbw100 applet = new Tcpbw100(useSSL);
        applet._bIsApplication = true;
        if (args.length > 1) {applet._sClient = args[1];}
        frame.getContentPane().add(applet);
        frame.setSize(700, 320);
        applet.init();
        applet.setsHostName(args[0]);
        applet.start();
        frame.setVisible(true);
        return applet;
    }

    //
    // Accessor methods for public variables
    //

    public String get_c2sspd() {return Double.toString((pub_c2sspd));} // Expressed as MiB using base 10
    public String get_s2cspd() {return Double.toString(pub_s2cspd);} // Expressed as MiB using base 10
    public String get_CurRwinRcvd() {return Integer.toString(pub_CurRwinRcvd);}
    public String get_MaxRwinRcvd() {return Integer.toString(pub_MaxRwinRcvd);}
    public String get_Ping() {return Integer.toString(pub_MinRTT);}
    public String get_MaxRTT() {return Integer.toString(pub_MaxRTT);}
    public String get_loss() {return Double.toString(pub_loss);}
    public String get_avgrtt() {return Double.toString(pub_avgrtt);}
    public String get_CurRTO() {return Integer.toString(pub_CurRTO);}
    public String get_SACKsRcvd() {return Integer.toString(pub_SACKsRcvd);}
    public String get_osVer() {return pub_osVer;}
    public String get_pluginVer() {return pub_pluginVer;}
    public String get_host() {return pub_host;}
    public String get_osName() {return pub_osName;}
    public String get_osArch() {return pub_osArch;}

    public String get_mismatch() {
        String result;
        if (pub_mismatch == 0) {result = "no";}
        else {result = "yes";}
        return result;
    }

    public String get_Bad_cable() {
        String result;
        if (pub_Bad_cable == 1) {result = "yes";}
        else {result = "no";}
        return result;
    }

    public String get_congestion() {
        String result;
        if (pub_congestion == 1) {result = "yes";}
        else {result = "no";}
        return result;
    }

    public String get_cwndtime() {return Double.toString(pub_cwndtime);}
    public String get_AccessTech() {return pub_AccessTech;}
    public String get_rcvrLimiting() {return Double.toString(pub_pctRcvrLimited);}

    public String get_optimalRcvrBuffer() {
        return Integer.toString(pub_MaxRwinRcvd * NDTConstants.KILO_BITS); //buffer size in bits
    }

    public String get_clientIP() {return pub_clientIP;}
    public String get_natStatus() {return pub_natBox;}
    public String get_DupAcksOut() {return Integer.toString(pub_DupAcksOut);}
    public String get_DupAcksIn() {return Integer.toString(pub_DupAcksIn);}

    public String get_TimeStamp() {
        String result = "unknown";
        if (pub_TimeStamp != null) {result = pub_TimeStamp.toString();}
        return result;
    }

    // get PC buffer imposed throughput limit
    public String get_PcBuffSpdLimit() {return Double.toString(rwin / rttsec);}
    public String get_jitter() {return Integer.toString((pub_MaxRTT - pub_MinRTT));}
    public String get_WaitSec() {return Integer.toString((pub_CurRTO * pub_Timeouts) / 1000);}
    public String get_errmsg() {return pub_errmsg;}
    public String get_diagnosis() {return pub_diagnosis;}
    public String get_statistics() {return pub_statistics;}
    public String get_status() {return pub_status;}

    public String get_instSpeed() {
        //Get speed in bits, hence multiply by 8 for byte->bit conversion
        return Double.toString((NDTConstants.EIGHT * pub_bytes) / (System.currentTimeMillis() - pub_time));
    }

    /**
     * Set UserAgent String containing browser details.
     *
     * @return String UserAgent details set locally
     * @see UserAgentTools
     */
    public String getUserAgent() {return _sUserAgent;}

    /**
     * Set UserAgent String.
     *
     * @param paramStrUserAgent UserAgent String to be set locally
     * @see UserAgentTools
     */
    public void setUserAgent(String paramStrUserAgent) {this._sUserAgent = paramStrUserAgent;}

    /**
     * Get Client-&gt;Server fire-wall test results.
     *
     * @return integer indicating C-&gt;S test results
     */
    public int getC2sSFWTestResults() {return this._iC2sSFWResult;}

    /**
     * Set Client-&gt;Server fire-wall test results.
     *
     * @param iParamC2SRes integer indicating C-&gt;S test results
     */
    public void setC2sSFWTestResults(int iParamC2SRes) {this._iC2sSFWResult = iParamC2SRes;}

    /**
     * Get Server-&gt;Client fire-wall test results.
     *
     * @return integer indicating C-&gt;S test results
     */
    public int getS2cSFWTestResults() {return this._iS2cSFWResult;}

    /**
     * Set server-&gt;Client fire-wall test results.
     *
     * @param iParamS2CRes integer indicating C-&gt;S test results
     *
     */
    public void setS2cSFWTestResults(int iParamS2CRes) {this._iS2cSFWResult = iParamS2CRes;}

    //
    // End of accessor methods
    //

    /* I2P */

    public boolean isTestInProgress() {return _bTestInProgress.get();}

    /**
     * Class to start tests in a thread. Starts by disabling all buttons, and invokes the dottcp() method.
     * This thread is stopped when the number of tests that was configured to be run have all completed,
     * or if the user stops it by interrupting from the GUI. Once the tests have been run, the buttons are
     * enabled so that results can be viewed in detail.
     */
    class TestWorker implements Runnable {
        // I2P
        public void run() {
            if (_bTestInProgress.compareAndSet(false, true)) {
                try {run2();}
                finally {_bTestInProgress.set(false);}
            } else {_log.warn("Test in progress, not running another one");}
        }

        private void run2() {
            int testNo = 1;
            int testsNum = ((Integer) _spinnerTestCount.getValue()).intValue();
            _buttonStartTest.setEnabled(false);
            _buttonDetails.setEnabled(false);
            _buttonStatistics.setEnabled(false);
            _spinnerTestCount.setEnabled(false);

            // StatusPanel sPanel = new StatusPanel(testsNum);
            // re-arch. Replaced above by the line below
            String sTempEnable = getParameter("enableMultipleTests");

            /*
             * Create status panel based on whether multiple tests are enabled.
             * If not, then the progress bar displays just the specific test (middlebox, C2S, firewall etc).
             * If yes, then the progress bar also shows the progress on the number of tests.
             */
            StatusPanel sPanel = new StatusPanel(testsNum, sTempEnable);
            synchronized (Tcpbw100.this) {_sPanel = sPanel;}
            getContentPane().add(BorderLayout.NORTH, sPanel);
            getContentPane().validate();
            getContentPane().repaint();

            try {
                while (true) {
                    if (sPanel.wantToStop()) {
                        _log.warn("cancelled");
                        break;
                    }
                    if (testsNum == 0) {
                        _resultsTxtPane.append("\n** " + _resBundDisplayMsgs.getString("startingTest") + " " + testNo + " **\n");
                    } else {
                        _resultsTxtPane.append("\n** " + _resBundDisplayMsgs.getString("startingTest") + " " + testNo + " " +
                                               _resBundDisplayMsgs.getString("of") + " " + testsNum + " **\n");
                    }
                    dottcp(sPanel);
                    if (testNo == testsNum) {break;} // If test count scheduled is complete, quit
                    if (sPanel.wantToStop()) { // If user stops the test, quit
                        _log.warn("Bandwidth test was cancelled");
                        break;
                    }
                    sPanel.setText("");
                    sPanel.endTest();
                    testNo += 1; // increment test count

                    /*
                     * This iteration of tests is now complete.
                     * Enable all buttons and output so that user can view details of results.
                     */
                    _buttonDetails.setEnabled(true);
                    _buttonStatistics.setEnabled(true);
                    _txtStatistics.append("\n** " + _resBundDisplayMsgs.getString("test") + " " + testNo + " **\n");
                    _txtDiagnosis.append("\n** " + _resBundDisplayMsgs.getString("test") + " " + testNo + " **\n");

                }
            } catch (Exception e) {
                String sMessage = NDTUtils.isEmpty(e.getMessage())
                        ? _resBundDisplayMsgs.getString("withoutMessage")
                        : e.getMessage();

                _bFailed = true;
                _sErrMsg = _resBundDisplayMsgs.getString("unexpectedException") + " (" + e.getClass().getName() + "): " + sMessage + "\n";
                _log.error(_sErrMsg, e);
            }

            // If test failed due to any reason, mark failure reason too
            if (_bFailed) {
                _resultsTxtPane.append(_sErrMsg);
                pub_isReady = "failed";
                pub_errmsg = _sErrMsg;
            }

            // Enable all buttons. Continue activities to mark status as complete
            _buttonDetails.setEnabled(true);
            _buttonStatistics.setEnabled(true);
            _spinnerTestCount.setEnabled(true);
            showStatus(_resBundDisplayMsgs.getString("done2"));
            _resultsTxtPane.append("\n" + _resBundDisplayMsgs.getString("clickStart2") + "\n");
            _buttonStartTest.setEnabled(true);
            getContentPane().remove(sPanel);
            getContentPane().validate();
            getContentPane().repaint();
        }
    } // end inner class

    /**
     * "Remote Control" function - invoke NDT' runtest() method from the API
     */
    public void run_test() {
        pub_errmsg = "Test in progress.";
        runtest();
    }

    /**
     * Initialize the base NDT window Applet init() method
     */
    public void init() {
        if (getParameter("country") != null) {_sCountry = getParameter("country");}
        if (getParameter("language") != null) {_sLang = getParameter("language");}
        if (getParameter("client") != null) {_sClient = getParameter("client");}

        try {
            String lang = _context.getProperty("routerconsole.lang");
            if (lang != null) {
                _localeObj = new Locale(lang);
                _sLang = lang;
            } else {_localeObj = Locale.getDefault();}
            _resBundDisplayMsgs = ResourceBundle.getBundle(NDTConstants.TCPBW100_MSGS, _localeObj);

            // Replaced method call to initialize _resBundDisplayMsgs for access by class
            // NDTConstants.initConstants(locale);
            NDTConstants.initConstants(_sLang, _sCountry);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Error while loading language files:\n" + e.getMessage());
            _log.warn("bundle", e);
        }

        createMainWindow();

        // Autorun functionality
        _sIsAutoRun = getParameter("autoRun");
        if ((_sIsAutoRun != null) && _sIsAutoRun.equals("true")) {
            pub_errmsg = "Test in progress.";
            runtest();
        }
    }

    /**
     * Initialize the "main" window. The main window is composed of 1. The
     * results pane, which describes the process and displays their results 2.
     * The buttons pane, which houses all the buttons for various options
     */
    private void createMainWindow() {
        // set content manager
        getContentPane().setLayout(new BorderLayout());

        // Start with status set to "Ready" to perform tests
        showStatus(_resBundDisplayMsgs.getString("ready"));

        // initialize
        _bFailed = false;

        // Results panel
        _resultsTxtPane = new ResultsTextPane();
        _resultsTxtPane.append(NDTConstants.NDT_TITLE_STR + NDTConstants.VERSION + "\n");
        _resultsTxtPane.setEditable(false);
        getContentPane().add(new JScrollPane(_resultsTxtPane));
        _resultsTxtPane.append(_resBundDisplayMsgs.getString("clickStart") + "\n");

        // Panel to add all buttons
        Panel buttonsPanel = new Panel();

        // Add "start" button
        _buttonStartTest = new JButton(_resBundDisplayMsgs.getString("start"));
        _buttonStartTest.addActionListener(this);
        buttonsPanel.add(_buttonStartTest);

        // Add "statistics" button
        _buttonStatistics = new JButton(_resBundDisplayMsgs.getString("statistics"));
        _buttonStatistics.addActionListener(this);
        if (getParameter("disableStatistics") == null) {buttonsPanel.add(_buttonStatistics);}
        _buttonStatistics.setEnabled(false);

        // Add "Details" button
        _buttonDetails = new JButton(_resBundDisplayMsgs.getString("moreDetails"));
        _buttonDetails.addActionListener(this);
        if (getParameter("disableDetails") == null) {buttonsPanel.add(_buttonDetails);}
        _buttonDetails.setEnabled(false);

        // add buttons panel to the main window
        getContentPane().add(BorderLayout.SOUTH, buttonsPanel);

        // "Options" panel components
        // 1. Is IPv6 preferred?
        _chkboxPreferIPv6 = new JCheckBox(_resBundDisplayMsgs.getString("preferIPv6"));
        // I2P
        // IPv6 unreliable, only prefer if we don't have a IPv4 address
        _chkboxPreferIPv6.setSelected(!Addresses.isConnected());
        _chkboxPreferIPv6.addActionListener(this);
        // 2. Conduct default tests?
        _chkboxDefaultTest = new JCheckBox(_resBundDisplayMsgs.getString("defaultTests"));
        _chkboxDefaultTest.setSelected(true);
        // 3. configure number of tests
        SpinnerNumberModel model = new SpinnerNumberModel();
        model.setMinimum(Integer.valueOf(0));
        model.setValue(Integer.valueOf(1));
        _spinnerTestCount.setModel(model);
        _spinnerTestCount.setPreferredSize(new Dimension(60, 20));

    } // createDiagnoseWindow() ends

    /**
     * Create the "More details" window.
     */
    public void createDiagnoseWindow() {
        // create new frame
        if (_frameWeb100Vars == null) {_frameWeb100Vars = new NewFrame(this);}

        // Get title for this window
        _frameWeb100Vars.setTitle(_resBundDisplayMsgs.getString(_sServerType + "Var"));
        Panel buttons = new Panel();
        _frameWeb100Vars.getContentPane().add("South", buttons);

        // Create Text area for displaying results, add "Heading"
        _txtDiagnosis = new JTextArea(_resBundDisplayMsgs.getString(_sServerType + "KernelVar") + ":\n", 15, 70);
        _txtDiagnosis.setEditable(true);

        // Now place all the buttons
        _frameWeb100Vars.getContentPane().add(new JScrollPane(_txtDiagnosis));
        _frameWeb100Vars.pack();
    } // createDiagnoseWindow() ends

    /**
     * Create the "Statistics" window.
     */
    public void createStatsWindow() {
        // create new frame
        if (_frameDetailedStats == null) {_frameDetailedStats = new NewFrame(this);}
        _frameDetailedStats.setTitle(_resBundDisplayMsgs.getString("detailedStats"));

        // create panel for buttons
        Panel buttons = new Panel();
        _frameDetailedStats.getContentPane().add("South", buttons);

        // Text area for Statistics, add "heading"
        _txtStatistics = new JTextArea(_resBundDisplayMsgs.getString(_sServerType + "Stats") + ":\n", 25, 70);
        _txtStatistics.setEditable(false);

        // Place all components
        _frameDetailedStats.getContentPane().add(new JScrollPane(_txtStatistics));
        _frameDetailedStats.pack();
    } // createStatsWindow()

    public void createOptionsWindow() {}

    /**
     * Run the Thread that calls the "dottcp" method to run tests. This method
     * is called by the Applet's init method if user selected an "autorun"
     * option, is run individually if user presses the "start button", or, is
     * internally invoked by the API call.
     */
    synchronized public void runtest() {
        pub_status = "notStarted";
        new I2PAppThread(new TestWorker(), "TestWorker").start();
    }

    /**
     * Action handler method called when an associated action is performed
     *
     * @param paramEventObj Event object that prompted the call
     */
    public void actionPerformed(ActionEvent paramEventObj) {
        Object source = paramEventObj.getSource();

        // Start the test
        if (source == _buttonStartTest) {
            if (_frameWeb100Vars != null) {
                _frameWeb100Vars.toBack();
                _frameWeb100Vars.dispose();
                _frameWeb100Vars = null;
            }

            if (_frameDetailedStats != null) {
                _frameDetailedStats.toBack();
                _frameDetailedStats.dispose();
                _frameDetailedStats = null;
            }

            pub_errmsg = "Test in progress.";
            runtest();
        } else if (source == _buttonDetails) { // show details of tests since that button was clicked
            _frameWeb100Vars.setResizable(true);
            _frameWeb100Vars.setVisible(true);
        } else if (source == _buttonStatistics) { // Show "statistics" window
            _frameDetailedStats.setResizable(true);
            _frameDetailedStats.setVisible(true);
        } else if (source == _chkboxPreferIPv6) {setsHostName(sHostName);} // prefer IPv6 checkbox
    } // actionPerformed()

    /**
     * Display current status in Applet window.
     *
     * @param msg String value of status
     * */
    public void showStatus(String msg) {
        synchronized(this) {_displayStatus = msg;}
        if (_log.shouldWarn()) {_log.warn("Bandwidth test status: " + msg);}
    }

    /**
     *  I2P
     *  Translated status, not HTML escaped.
     */
    public synchronized String getStatus() {return _displayStatus;}

    public boolean test_mid(Protocol paramProtoObj) throws IOException {return false;}
    public boolean test_sfw(Protocol protocolObj) throws IOException {return false;}

    /**
     * Client to server throughput test. This test performs 10 seconds
     * memory-to-memory data transfer to test achievable network bandwidth.
     *
     * @param paramProtoObj Protocol Object used to exchange messages
     * @return boolean, true if test was not completed, false if test was completed.
     * @throws IOException when sending/receiving messages from server fails
     * @see Protocol#recv_msg(Message msgParam)
     * @see Protocol#send_json_msg(byte bParamType, byte[] baParamTab)
     */
    public boolean test_c2s(Protocol paramProtoObj) throws IOException {
        // Initialise for 64 Kb
        _yabuff2Write = new byte[64 * NDTConstants.KILO_BITS];
        Message msg = new Message();
        // start C2S throughput tests
        if ((_yTests & NDTConstants.TEST_C2S) == NDTConstants.TEST_C2S) {
            showStatus(_resBundDisplayMsgs.getString("outboundTest"));
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("runningOutboundTest") + " ");
            _txtStatistics.append(_resBundDisplayMsgs.getString("runningOutboundTest") + " ");
            pub_status = "runningOutboundTest";

            if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // msg receive/read error
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                return true;
            }
            // Initial message expected from server is a TEST_PREPARE
            if (msg.getType() != MessageType.TEST_PREPARE) { // any other msg is error indicator
                _sErrMsg = _resBundDisplayMsgs.getString("outboundWrongMessage") + "\n";
                if (msg.getType() == MessageType.MSG_ERROR) {
                    _sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";
                }
                return true;
            }
            // Server sends port number to bind to in the TEST_PREPARE
            int iC2sport = parseMsgBodyToInt(new String(msg.getBody()));

            // client connects to this port
            final Socket outSocket;
            try {outSocket = newSocket(hostAddress, iC2sport);}
            catch (UnknownHostException e) {
                _log.warn("Don't know about host: " + sHostName, e);
                _sErrMsg = _resBundDisplayMsgs.getString("unknownServer") + "\n";
                return true;
            } catch (IOException e) {
                _log.warn("Couldn't get 2nd connection to: " + sHostName, e);
                _sErrMsg = _resBundDisplayMsgs.getString("serverBusy15s") + "\n";
                return true;
            }

            // Get server IP address from the outSocket.
            pub_host = outSocket.getInetAddress().getHostAddress().toString();

            // Get output Stream from socket to write data into
            final OutputStream outStream = outSocket.getOutputStream();

            // Wait here for signal from server application
            // This signal tells the client to start pumping out data
            if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // error reading/receiving message
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                return true;
            }
            // Expect a TEST_START message from server now. Any other message type is an error
            if (msg.getType() != MessageType.TEST_START) {
                _sErrMsg = _resBundDisplayMsgs.getString("outboundWrongMessage") + "\n";
                if (msg.getType() == MessageType.MSG_ERROR) {
                    _sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";
                }
                return true;
            }

            // Fill buffer upto NDTConstants.PREDEFNED_BUFFER_SIZE packets
            byte c = '0';
            int i;
            for (i = 0; i < _iLength; i++) {
                if (c == 'z') {c = '0';}
                _yabuff2Write[i] = c++;
            }
            if (_log.shouldWarn()) {_log.warn("******Send buffer size =" + i);}

            _iPkts = 0;
            _dTime = System.currentTimeMillis();
            pub_time = _dTime;

            new I2PAppThread("NDT Sleeper") {

                public void run() {
                    try {Thread.sleep(10000);} // sleep for 10s
                    catch (InterruptedException e) {
                        _log.warn("Thread interrupted", e);
                        // Thread was interrupted while timing 10 seconds of the C->S test.
                        // So, streaming 10 seconds of data may not be complete.
                        // But, the throughput is correctly calculated based on number of sent packets
                    }
                    try {
                        outStream.close();
                        outSocket.close();
                    } catch (IOException e) {
                        _log.warn("Caught IOException while closing stream after thread interrupted", e);
                    }
                }
            }.start();

            PeriodicTimer c2sspdUpdateTimer = new PeriodicTimer() {
                @Override
                public void timeReached() {
                    pub_c2sspd = ((NDTConstants.EIGHT * _iPkts * _yabuff2Write.length) / NDTConstants.KILO) / (System.currentTimeMillis() - _dTime);
                    schedule(_c2sspdUpdateTime);
                }
            };

            // While the 10s timer ticks, write buffer data into server socket
            while (true) {
                try {outStream.write(_yabuff2Write, 0, _yabuff2Write.length);}
                catch (SocketException e) { // normal after 10 seconds
                    _log.debug("SocketException while writing to server (normal)", e);
                    break;
                }
                catch (IOException ioe) {
                    _log.warn("Client socket timed out");
                    break;
                }
                /*
                 * In both cases above, thread was interrupted while timing 10 seconds of the C->S test.
                 * So, streaming 10 seconds of data may not be complete.
                 * But, the throughput is correctly calculated based on the number of sent packets
                 */

                _iPkts++;
                // number of bytes sent = (num of iterations) X (buffer size)
                pub_bytes = (_iPkts * _iLength);
            }

            c2sspdUpdateTimer.cancel();
            _dTime = System.currentTimeMillis() - _dTime;
            _log.warn(_dTime + " millisec test completed" + "," + _yabuff2Write.length + ","+ _iPkts);
            if (_dTime == 0) {_dTime = 1;}

            // Calculate C2S throughput in kbps
            _log.warn((NDTConstants.EIGHT * _iPkts * _yabuff2Write.length) / _dTime + " KB/s outbound"); //*8 for calculating bits

            _dC2sspd = ((NDTConstants.EIGHT * _iPkts * _yabuff2Write.length) / NDTConstants.KILO) / _dTime;

            // The client has stopped streaming data, and the server is now expected to send a TEST_MSG message with the throughout it calculated.
            // So, it's now time to receive the throughput (c2sspd) from the server.

            if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // error reading/receiving data
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                return true;
            }
            if (msg.getType() != MessageType.TEST_MSG) { // if not TEST_MSG, wrong, unexpected
                _sErrMsg = _resBundDisplayMsgs.getString("outboundWrongMessage");
                if (msg.getType() == MessageType.MSG_ERROR) {
                    _sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";
                }
                return true;
            }
            // Get throughput as calculated by server
            String tmpstr3;
            if (jsonSupport) {tmpstr3 = JSONUtils.getSingleMessage(new String(msg.getBody()));}
            else {tmpstr3 = new String(msg.getBody());}

            _dSc2sspd = Double.parseDouble(tmpstr3) / NDTConstants.KILO;

            // Print results in the most convenient units (kbps or Mbps)
            if (_dSc2sspd < 1.0) {
                _resultsTxtPane.append(NDTUtils.prtdbl(_dSc2sspd * NDTConstants.KILO) + "kb/s\n");
                _txtStatistics.append(NDTUtils.prtdbl(_dSc2sspd * NDTConstants.KILO) + "kb/s\n");
            } else {
                _resultsTxtPane.append(NDTUtils.prtdbl(_dSc2sspd) + "Mb/s\n");
                _txtStatistics.append(NDTUtils.prtdbl(_dSc2sspd) + "Mb/s\n");
            }

            // Expose upload speed to JavaScript clients
            pub_c2sspd = _dSc2sspd;

            // Server should close test session with a TEST_FINALIZE message
            if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // read/receive error
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                return true;
            }
            if (msg.getType() != MessageType.TEST_FINALIZE) { // all other types unexpected, erroneous
                _sErrMsg = _resBundDisplayMsgs.getString("outboundWrongMessage");
                if (msg.getType() == MessageType.MSG_ERROR) {
                    _sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";
                }
                return true; // true indicates test incomplete
            }
        }
        // false indicates test's completion
        return false;
    }

    /**
     * S2C throughput test to measure network bandwidth from server to client.
     *
     * @param paramProtoObj Protocol Object used to exchange messages
     * @param paramSocketObj Socket Object to write/read NDTProtocol control messages
     * @return boolean, true if test was not completed, false if test was completed.
     * @throws IOException When sending/receiving messages from server fails
     * @see Protocol#recv_msg(Message msgParam)
     * @see Protocol#send_json_msg(byte bParamType, byte[] baParamTab)
     */
    public boolean test_s2c(Protocol paramProtoObj, Socket paramSocketObj) throws IOException {
        // byte buff[] = new byte[8192];
        byte buff[] = new byte[NDTConstants.PREDEFINED_BUFFER_SIZE];
        Message msg = new Message();
        // start S2C tests
        if ((_yTests & NDTConstants.TEST_S2C) == NDTConstants.TEST_S2C) {
            showStatus(_resBundDisplayMsgs.getString("inboundTest"));
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("runningInboundTest") + " ");
            _txtStatistics.append(_resBundDisplayMsgs.getString("runningInboundTest") + " ");
            pub_status = "runningInboundTest";

            // Server sends TEST_PREPARE with port to bind to as message body
            if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // read/receive error
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                return true;
            }
            if (msg.getType() != MessageType.TEST_PREPARE) { // no other message type expected
                _sErrMsg = _resBundDisplayMsgs.getString("inboundWrongMessage") + "\n";
                if (msg.getType() == MessageType.MSG_ERROR) {
                    _sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";
                }
                return true;
            }
            // get port to bind to for S2C tests
            int iS2cport = parseMsgBodyToInt(new String(msg.getBody()));

            // Create socket and bind to port as instructed by server
            Socket inSocket;
            try {inSocket = newSocket(hostAddress, iS2cport);}
            catch (UnknownHostException e) {
                _log.warn("Don't know about host: " + sHostName, e);
                _sErrMsg = "unknown server\n";
                return true;
            } catch (IOException e) {
                _log.warn("Couldn't get 3rd connection to: " + sHostName, e);
                _sErrMsg = "Server Failed while receiving data\n";
                return true;
            }

            // Get input stream to read bytes from socket
            InputStream srvin = inSocket.getInputStream();
            long iBitCount = 0;
            int inlth;

            // Wait here for signal from server application
            // Server now sends a TEST_START message
            if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // erroneous read/receive
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                return true;
            }

            if (msg.getType() != MessageType.TEST_START) { // no other type of message expected
                _sErrMsg = _resBundDisplayMsgs.getString("inboundWrongMessage") + "\n";
                if (msg.getType() == MessageType.MSG_ERROR) {
                    _sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";
                }
                return true;
            }

            // Set socket timeout to 15 seconds
            inSocket.setSoTimeout(15000);
            _dTime = System.currentTimeMillis();
            pub_time = _dTime;

            PeriodicTimer s2cspdUpdateTimer = new PeriodicTimer() {
                @Override
                public void timeReached() {
                    pub_s2cspd = ((NDTConstants.EIGHT * pub_bytes) / NDTConstants.KILO) / (System.currentTimeMillis() - _dTime);
                    schedule(_s2cspdUpdateTime);
                }
            };

            // Read data sent by server
            try {
                while ((inlth = srvin.read(buff, 0, buff.length)) > 0) {
                    iBitCount += inlth; // increment bit count
                    pub_bytes = iBitCount;
                    if ((System.currentTimeMillis() - _dTime) > 14500) {break;}
                }
            } catch (IOException ioExcep) {
                // New addition to handle Exception
                _log.warn("Couldn't perform s2c testing to: " + sHostName, ioExcep);
                _sErrMsg = "Server Failed while reading socket data\n";
                return true;
            } finally {s2cspdUpdateTimer.cancel();}

            // Get time duration during which bytes were received
            _dTime = System.currentTimeMillis() - _dTime;
            _log.warn(iBitCount + " bytes " + (NDTConstants.EIGHT * iBitCount) / _dTime + " kb/s " + _dTime / NDTConstants.KILO + " secs");

            // Calculate throughput
            _dS2cspd = ((NDTConstants.EIGHT * iBitCount) / NDTConstants.KILO) / _dTime;

            /*
             * Once the "send" window is complete, server sends TEST_MSG message with throughout as calculated
             * at its end, unsent data queue size, and total sent byte count, separated by spaces.
             * Receive the s2cspd from the server
             */
            if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // error in read/receive of msg
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                return true;
            }
            // Only message of type TEST_MSG expected from server at this point
            if (msg.getType() != MessageType.TEST_MSG) {
                _sErrMsg = _resBundDisplayMsgs.getString("inboundWrongMessage") + "\n";
                if (msg.getType() == MessageType.MSG_ERROR) {
                    _sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";
                }
                return true;
            }
            // Get data from payload
            if (jsonSupport) {
                try {
                    String tmpstr3 = new String(msg.getBody());
                    _dSs2cspd = Double.parseDouble(JSONUtils.getValueFromJsonObj(tmpstr3, "ThroughputValue")) / NDTConstants.KILO;
                    _iSsndqueue = Integer.parseInt(JSONUtils.getValueFromJsonObj(tmpstr3, "UnsentDataAmount"));
                    _dSbytes = Double.parseDouble(JSONUtils.getValueFromJsonObj(tmpstr3, "TotalSentByte"));
                } catch (Exception e) {
                    _sErrMsg = _resBundDisplayMsgs.getString("inboundWrongMessage") + "\n";
                    _log.error(_sErrMsg, e);
                    return true;
                }
            }
            else {
                try {
                    String tmpstr3 = new String(msg.getBody());
                    int k1 = tmpstr3.indexOf(" ");
                    int k2 = tmpstr3.substring(k1 + 1).indexOf(" ");
                    _dSs2cspd = Double.parseDouble(tmpstr3.substring(0, k1)) / NDTConstants.KILO;
                    _iSsndqueue = Integer.parseInt(tmpstr3.substring(k1 + 1).substring(0, k2));
                    _dSbytes = Double.parseDouble(tmpstr3.substring(k1 + 1).substring(k2 + 1));
                } catch (Exception e) {
                    _sErrMsg = _resBundDisplayMsgs.getString("inboundWrongMessage") + "\n";
                    _log.error(_sErrMsg, e);
                    return true;
                }
            }

            // Represent throughput using optimal units (kbps / mbps)
            if (_dS2cspd < 1.0) {
                _resultsTxtPane.append(NDTUtils.prtdbl(_dS2cspd * NDTConstants.KILO) + "kb/s\n");
                _txtStatistics.append(NDTUtils.prtdbl(_dS2cspd * NDTConstants.KILO) + "kb/s\n");
            } else {
                _resultsTxtPane.append(NDTUtils.prtdbl(_dS2cspd) + "Mb/s\n");
                _txtStatistics.append(NDTUtils.prtdbl(_dS2cspd) + "Mb/s\n");
            }

            // Expose download speed to JavaScript clients
            pub_s2cspd = _dS2cspd;
            pub_status = "done";

            // Perform wrap-up activities for test
            srvin.close();
            inSocket.close();

            // Client has to send its throughput to server inside a TEST_MSG
            buff = Double.toString(_dS2cspd * NDTConstants.KILO).getBytes();
            String tmpstr4 = new String(buff, 0, buff.length);
            _log.warn("Sending '" + tmpstr4 + "' back to server");
            paramProtoObj.send_json_msg(MessageType.TEST_MSG, buff);

            // Get web100 variables from server
            _sTestResults = "";
            int i = 0;

            // Try setting a 5 second timer here to break out if the read fails.
            paramSocketObj.setSoTimeout(5000);
            try {
                for (;;) {
                    if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // msg could not be read/received correctly
                        _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                        return true;
                    }
                    if (msg.getType() == MessageType.TEST_FINALIZE) {break;} // All web100 variables have been sent

                    /*
                     * Only a message of TEST_MSG type containing the Web100 variables is expected.
                     * Every other type of message is indicative of errors
                     */
                    if (msg.getType() != MessageType.TEST_MSG) {
                        _sErrMsg = _resBundDisplayMsgs.getString("inboundWrongMessage") + "\n";
                        if (msg.getType() == MessageType.MSG_ERROR) {
                            _sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";
                        }
                        return true;
                    }
                    // Get all web100 variables as name-value string pairs
                    if (jsonSupport) {_sTestResults += JSONUtils.getSingleMessage(new String(msg.getBody()));}
                    else {_sTestResults += new String(msg.getBody());}
                    i++;
                } // end for
            } catch (IOException ioExcep) {
                // new addition to handle Exception
                _sErrMsg = _resBundDisplayMsgs.getString("s2cThroughputFailed") + "\n";
                _log.warn("Couldn't perform s2c testing to: " + sHostName, ioExcep);
                _sErrMsg += "Server Failed while reading socket data\n";
                return true;
            }
        }
        pub_status = "done";
        return false; // false indicating no stoppage was encountered
    }

    /**
     * The META test allows the Client to send an additional information to the Server that basically
     * gets included along with the overall set of test results.
     *
     * @param paramProtoObj Protocol Object used to exchange protocol messages
     * @return boolean, true if test was completed, false if test is incomplete.
     * @throws IOException When sending/receiving messages from server fails
     * @see Protocol#recv_msg(Message msgParam)
     * @see Protocol#send_json_msg(byte bParamType, byte[] baParamTab) These methods indicate more information about IOException
     */
    public boolean test_meta(Protocol paramProtoObj, String application) throws IOException {
        Message msg = new Message();
        // Start META tests
        if ((_yTests & NDTConstants.TEST_META) == NDTConstants.TEST_META) {
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("sendingMetaInformation") + " ");
            _txtStatistics.append(_resBundDisplayMsgs.getString("sendingMetaInformation") + " ");
            pub_status = "sendingMetaInformation";

            // Server starts with a TEST_PREPARE message.
            if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // error, message not received correctly
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                return true;
            }
            if (msg.getType() != MessageType.TEST_PREPARE) {
                // only TEST_PREPARE message expected at this point
                _sErrMsg = _resBundDisplayMsgs.getString("metaWrongMessage") + "\n";
                if (msg.getType() == MessageType.MSG_ERROR) {
                    _sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";
                }
                return true;
            }

            // Server now send a TEST_START message
            if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // error, message not read/received correctly
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                return true;
            }

            // Only TEST_START message expected here. Everything else is unacceptable
            if (msg.getType() != MessageType.TEST_START) {
                _sErrMsg = _resBundDisplayMsgs.getString("metaWrongMessage") + "\n";
                if (msg.getType() == MessageType.MSG_ERROR) {
                    _sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";
                }
                return true;
            }

            /*
             * As a response to the Server's TEST_START message, client responds with TEST_MSG.
             * These messages may be used, as below, to send configuration data name-value pairs.
             * Note that there are length constraints to keys- values: 64/256 characters respectively
             */
            _log.warn("USERAGENT " + getUserAgent());
            paramProtoObj.send_json_msg(MessageType.TEST_MSG, (NDTConstants.META_CLIENT_OS + ":" + "Linux").getBytes());
            paramProtoObj.send_json_msg(MessageType.TEST_MSG, (NDTConstants.META_BROWSER_OS + ":" + UserAgentTools.getBrowser(getUserAgent())[2]).getBytes());
            paramProtoObj.send_json_msg(MessageType.TEST_MSG, (NDTConstants.META_CLIENT_KERNEL_VERSION + ":" + "4.15.0-38-generic").getBytes());
            paramProtoObj.send_json_msg(MessageType.TEST_MSG, (NDTConstants.META_CLIENT_VERSION + ":" + NDTConstants.VERSION).getBytes());
            paramProtoObj.send_json_msg(MessageType.TEST_MSG, (NDTConstants.META_CLIENT_APPLICATION + ":" + application) .getBytes());

            // Client can send any number of such meta data in a TEST_MSG format, and signal the end of the transmission using an empty TEST_MSG
            paramProtoObj.send_json_msg(MessageType.TEST_MSG, new byte[0]);

            // The server now closes the META test session by sending a TEST_FINALIZE message
            if (paramProtoObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // error, message cannot be read/received properly
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                return true;
            }
            if (msg.getType() != MessageType.TEST_FINALIZE) { // Only this message type is expected
                _sErrMsg = _resBundDisplayMsgs.getString("metaWrongMessage");
                if (msg.getType() == MessageType.MSG_ERROR) {_sErrMsg += "ERROR MSG: " + parseMsgBodyToInt(new String(msg.getBody()), 16) + "\n";}
                return true;
            }
            // Display status as "complete"
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("done") + "\n");
            _txtStatistics.append(_resBundDisplayMsgs.getString("done") + "\n");
        }

        pub_status = "done"; // completed tests
        return false; // status is false indicating test-failure=false
    }

    /**
     * Method to run tests and interpret the results sent by the server
     *
     * @param sPanel StatusPanel object to describe status of tests
     * @throws IOException When sending/receiving messages from server fails
     * @see Protocol#recv_msg(Message msgParam)
     * @see Protocol#send_json_msg(byte bParamType, byte[] baParamTab)
     */
    public void dottcp(StatusPanel sPanel) throws IOException {
        Socket ctlSocket = null;
        if (!_bIsApplication) {

            /*
             * Enable NDT to test against a web100srv instance on a remote server.
             * Instead of using the getCodeBase().getHost() value for the testing server,
             * which assumes this applet is being served from the web100srv server,
             * use a parameter provided in the APPLET tag.
             *
             * Note that for this to work the applet must be signed because you are
             * potentially accessing a server outside the source domain.
             */
            String sTestingServer = getParameter("testingServer");

            // Fall back to the old behavior if the APPLET tag is not set
            if (sTestingServer == null) {sTestingServer = getCodeBase().getHost();}
            setsHostName(sTestingServer);
            pub_host = sHostName;
        }

        // The default control port used for the NDT tests session. NDT server listens to this port
        int ctlport = _useSSL ? NDTConstants.CONTROL_PORT_SSL : NDTConstants.CONTROL_PORT_DEFAULT;
        int i, wait;
        int iServerWaitFlag = 0; // flag indicating whether a wait message was already received once
        _bFailed = false; // Assign false to test result status initially

        try {
            // RAC Debug message
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("connectingTo") + " '" + sHostName +
                                   "' [" + hostAddress + "] " +
                                   _resBundDisplayMsgs.getString("toRunTest") + "\n");
            ctlSocket = newSocket(hostAddress, ctlport); // Create socket to host specified by user and the default port
        } catch (UnknownHostException e) {
            _log.warn("Don't know about host: " + sHostName, e);
            _sErrMsg = _resBundDisplayMsgs.getString("unknownServer") + "\n";
            _bFailed = true;
            return;
        } catch (IOException e) {
            _log.warn("Couldn't get the connection to: " + sHostName + " " + ctlport, e);
            _sErrMsg = _resBundDisplayMsgs.getString("serverNotRunning") + " (" + sHostName + ":" + ctlport + ")\n";
            _bFailed = true;
            return;
        }

        Protocol protocolObj = new Protocol(ctlSocket);
        Message msg = new Message();

        // The beginning of the protocol

        // Determine, and indicate to client about Inet6/4 address being used
        if (ctlSocket.getInetAddress() instanceof Inet6Address) {
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("connected") + " " + sHostName + _resBundDisplayMsgs.getString("usingIpv6") + "\n");
        } else {
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("connected") + " " + sHostName + _resBundDisplayMsgs.getString("usingIpv4") + "\n");
        }

        // Write our test suite request by sending a login message
        // _yTests indicates the requested test-suite
        byte [] send = new byte[NDTConstants.VERSION.length()+1];
        send[0] = _yTests;
        System.arraycopy(NDTConstants.VERSION.getBytes(), 0, send, 1, NDTConstants.VERSION.length());

        // I2P - adds tests as "tests" in json
        // https://github.com/measurement-kit/measurement-kit/blob/master/src/libmeasurement_kit/ndt/messages.cpp
        protocolObj.send_json_login_msg(MessageType.MSG_EXTENDED_LOGIN, send);
        ctlSocket.setSoTimeout(30*1000); // SSL in particular will hang here for several minutes

        // Read the specially crafted data that kicks off the old clients
        if (protocolObj.readn(msg, 13) != 13) {
            _sErrMsg = _resBundDisplayMsgs.getString("unsupportedClient") + "\n";
            _bFailed = true;
            try {ctlSocket.close();}
            catch (IOException ioe) {}
            return;
        }
        ctlSocket.setSoTimeout(60*1000);

        for (;;) {
            // If SRV_QUEUE message sent by NDT server does not indicate that the test session starts now, return
            if (protocolObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) {
                _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                _bFailed = true;
                return;
            }

            /*
             * SRV_QUEUE messages are only sent to queued clients with a message body that indicates one of a few statuses,
             * as will be hanlded individually below. Any other type of message at this stage is incorrect.
             */
            if (msg.getType() != MessageType.SRV_QUEUE) {
                if (!retry && !new String(msg.getBody()).equals("Invalid login message.")) {
                    jsonSupport = false;
                    retry = true;
                    try {
                        // RAC Debug message
                        _resultsTxtPane.append(_resBundDisplayMsgs.getString("unsupportedMsgExtendedLogin") + "\n");
                        // create socket to host specified by user and the default port
                        // we seem to always get here, why bother trying extended above?
                        if (ctlSocket != null) {
                            try {ctlSocket.close();}
                            catch (IOException ioe) {}
                        }
                        ctlSocket = newSocket(hostAddress, ctlport);
                    } catch (UnknownHostException e) {
                        _log.warn("Don't know about host: " + sHostName, e);
                        _sErrMsg = _resBundDisplayMsgs.getString("unknownServer") + "\n";
                        _bFailed = true;
                        return;
                    } catch (IOException e) {
                        _log.warn("Couldn't get the connection to: " + sHostName + " " + ctlport, e);
                        _sErrMsg = _resBundDisplayMsgs.getString("serverNotRunning") + " (" + sHostName + ":" + ctlport + ")\n";
                        _bFailed = true;
                        return;
                    }

                    protocolObj = new Protocol(ctlSocket);
                    protocolObj.setJsonSupport(false);
                    // The beginning of the protocol

                    // Determine, and indicate to client about Inet6/4 address being used
                    if (ctlSocket.getInetAddress() instanceof Inet6Address) {
                        _resultsTxtPane.append(_resBundDisplayMsgs.getString("connected") + " " + sHostName +
                                               _resBundDisplayMsgs.getString("usingIpv6") + "\n");
                    } else {
                        _resultsTxtPane.append(_resBundDisplayMsgs.getString("connected") + " " + sHostName +
                                               _resBundDisplayMsgs.getString("usingIpv4") + "\n");
                    }

                    protocolObj.send_msg(MessageType.MSG_LOGIN, _yTests);


                    if (protocolObj.readn(msg, 13) != 13) {
                        _sErrMsg = _resBundDisplayMsgs.getString("unsupportedClient") + "\n";
                        _bFailed = true;
                        return;
                    }
                    continue;
                } else {
                    _sErrMsg = _resBundDisplayMsgs.getString("loggingWrongMessage") + "\n";
                    _bFailed = true;
                    return;
                }
            }

            // Get wait flag value
            String tmpstr3 = new String(msg.getBody());
            wait = parseMsgBodyToInt(tmpstr3);
            _log.warn("wait flag received = " + wait);

            // SRV_QUEUE message received indicating "ready to start tests" status, proceed to running tests
            if (wait == NDTConstants.SRV_QUEUE_TEST_STARTS_NOW) {break;}

            if (wait == NDTConstants.SRV_QUEUE_SERVER_FAULT) {
                _sErrMsg = _resBundDisplayMsgs.getString("serverFault") + "\n";
                _bFailed = true;
                return;
            }

            if (wait == NDTConstants.SRV_QUEUE_SERVER_BUSY) {
                // First message from server, indicating server is busy. Quit
                if (iServerWaitFlag == 0) {
                    _sErrMsg = _resBundDisplayMsgs.getString("serverBusy") + "\n";
                    _bFailed = true;
                    return;
                } else { // Server fault, quit without further ado
                    _sErrMsg = _resBundDisplayMsgs.getString("serverFault") + "\n";
                    _bFailed = true;
                    return;
                }
            }

            // server busy, wait 60 s for previous test to finish
            if (wait == NDTConstants.SRV_QUEUE_SERVER_BUSY_60s) {
                _sErrMsg = _resBundDisplayMsgs.getString("serverBusy60s") + "\n";
                _bFailed = true;
                return;
            }

            if (wait == NDTConstants.SRV_QUEUE_HEARTBEAT) { // signal from the server to see if the client is still alive
                // Client has to respond with a "MSG_WAITING" to such heart-beat messages from server
                protocolObj.send_json_msg(MessageType.MSG_WAITING, _yTests);
                continue;
            }

            /*
             * Each test should take less than 30 seconds.
             * Tell them 60 sec * number of tests-suites waiting in the queue.
             * Note that server sends a number equal to the number of clients ==
             * number of minutes to wait before starting tests (i.e wait =
             * number of minutes to wait = number of queued clients)
             */
            wait = (wait * 60);
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("otherClient") + wait + _resBundDisplayMsgs.getString("seconds") + ".\n");
            iServerWaitFlag = 1; // mark variable as ==first message from server already encountered end waiting
        }

        /*
         * Tests can be started. Read server response again.
         * The server must send a message to verify version, and this is a MSG_LOGIN type of message
         */
        if (protocolObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) { // Not read correctly - protocol error
            _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
            _bFailed = true;
            return;
        }
        if (msg.getType() != MessageType.MSG_LOGIN) { // Only this type of message is expected at this stage ..every other message type is "wrong"
            _sErrMsg = _resBundDisplayMsgs.getString("versionWrongMessage") + "\n";
            _bFailed = true;
            return;
        }

        // Version compatibility between server-client must be verified
        String vVersion;
        if (jsonSupport) {vVersion = JSONUtils.getSingleMessage(new String(msg.getBody()));}
        else {vVersion = new String(msg.getBody());}

        if (!vVersion.startsWith("v")) {
            _sErrMsg = _resBundDisplayMsgs.getString("incompatibleVersion");
            _bFailed = true;
            return;
        }
        _log.warn("Server version: " + vVersion.substring(1));

        if (vVersion.endsWith("Web10G") || vVersion.endsWith("Web100")) {
            if (!vVersion.substring(1, vVersion.lastIndexOf('-')).equals(NDTConstants.VERSION)) {
                _resultsTxtPane.append(_resBundDisplayMsgs.getString("diffrentVersion") + " (" + vVersion.substring(1, vVersion.lastIndexOf('-')) + ")\n");
                _log.warn("WARNING: NDT server has different version number (" + vVersion.substring(1) + ")");
            }
        }
        else if (!vVersion.substring(1).equals(NDTConstants.VERSION)) {
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("diffrentVersion") + " (" + vVersion.substring(1) + ")\n");
            _log.warn("WARNING: NDT server has different version number (" + vVersion.substring(1) + ")");
        }

        // If we have connected to a Web10G server rebrand ourselves as such
        _sServerType = vVersion.endsWith("Web10G") ? "web10g" : "web100";

        // Only create the windows once we have connected to the server so this works
        createDiagnoseWindow();
        createStatsWindow();
        _frameWeb100Vars.toBack();
        _frameDetailedStats.toBack();

        /*
         * Read server message again. Server must send a message to negotiate the test suite, and this is
         * a MSG_LOGIN type of message which indicates the same set of tests as requested by the client earlier
         */
        if (protocolObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) {
            _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
            _bFailed = true;
            return;
        }
        // Only tests-negotiation message expected at this stage ..every other message type is "wrong"
        if (msg.getType() != MessageType.MSG_LOGIN) {
            _sErrMsg = _resBundDisplayMsgs.getString("testsuiteWrongMessage") + "\n";
            _bFailed = true;
            return;
        }

        // Get ids of tests to be run now
        String tmpstr;
        if (jsonSupport) {tmpstr = JSONUtils.getSingleMessage(new String(msg.getBody()));}
        else {tmpstr = new String(msg.getBody());}
        StringTokenizer tokenizer = new StringTokenizer(tmpstr, " ");

        // Run all tests requested, based on the ID. In each case, if tests cannot be successfully run, indicate reason
        while (tokenizer.hasMoreTokens()) {
            // None of test_xxx catch NumberFormatException, do it here so we don't kill the whole thing
            try {
            if (sPanel.wantToStop()) { // user has indicated decision to stop tests from GUI
                protocolObj.send_json_msg(MessageType.MSG_ERROR, "Manually stopped by the user".getBytes());
                protocolObj.close();
                ctlSocket.close();
                _sErrMsg = "\n" + _resBundDisplayMsgs.getString("stopped") + "\n";
                _bFailed = true;
                _log.warn(_sErrMsg);
                return;
            }
            int testId = Integer.parseInt(tokenizer.nextToken());
            switch (testId) {
            case NDTConstants.TEST_C2S:
                sPanel.setText(_resBundDisplayMsgs.getString("c2sThroughput"));
                if (test_c2s(protocolObj)) {
                    _resultsTxtPane.append(_sErrMsg);
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("c2sThroughputFailed") + "\n");
                    _yTests &= (~NDTConstants.TEST_C2S);
                }
                break;
            case NDTConstants.TEST_S2C:
                sPanel.setText(_resBundDisplayMsgs.getString("s2cThroughput"));
                if (test_s2c(protocolObj, ctlSocket)) {
                    _resultsTxtPane.append(_sErrMsg);
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("s2cThroughputFailed") + "\n");
                    _yTests &= (~NDTConstants.TEST_S2C);
                }
                break;
            case NDTConstants.TEST_META:
                sPanel.setText(_resBundDisplayMsgs.getString("meta"));
                if (test_meta(protocolObj, _sClient)) {
                    _resultsTxtPane.append(_sErrMsg);
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("metaFailed") + "\n");
                    _yTests &= (~NDTConstants.TEST_META);
                }
                break;
            default:
                _sErrMsg = _resBundDisplayMsgs.getString("unknownID") + "\n";
                _bFailed = true;
                return;
            }
            // None of test_xxx catch NumberFormatException, do it here so we don't kill the whole thing
            } catch (NumberFormatException nfe) {_log.warn("nfe", nfe);}
        }

        if (sPanel.wantToStop()) { // user has indicated decision to stop tests from GUI
            protocolObj.send_json_msg(MessageType.MSG_ERROR, "Manually stopped by the user".getBytes());
            protocolObj.close();
            ctlSocket.close();
            _sErrMsg = _resBundDisplayMsgs.getString("stopped") + "\n";
            _bFailed = true;
            _log.warn(_sErrMsg);
            return;
        }

        sPanel.setText(_resBundDisplayMsgs.getString("receiving"));

        // Get results of tests
        i = 0;
        try {
            for (;;) {
                if (protocolObj.recv_msg(msg) != NDTConstants.PROTOCOL_MSG_READ_SUCCESS) {
                    _sErrMsg = _resBundDisplayMsgs.getString("protocolError") + parseMsgBodyToInt(new String(msg.getBody()), 16) + " instead\n";
                    _bFailed = true;
                    return;
                }

                // Results obtained. "Log out" message received now
                if (msg.getType() == MessageType.MSG_LOGOUT) {break;}

                // Get results in the form of a human-readable string
                if (msg.getType() != MessageType.MSG_RESULTS) {
                    _sErrMsg = _resBundDisplayMsgs.getString("resultsWrongMessage") + "\n";
                    _bFailed = true;
                    return;
                }
                if (jsonSupport) {_sTestResults += JSONUtils.getSingleMessage(new String(msg.getBody()));}
                else {_sTestResults += new String(msg.getBody());}
                i++;
            }
        } catch (IOException e) {
            _log.warn("Couldn't complete tests while waiting for MSG_LOGOUT/MSG_RESULTS for " + sHostName, e);
            _sErrMsg = "Couldn't complete tests while waiting for MSG_LOGOUT/MSG_RESULTS for " + sHostName + ")\n";
            _bFailed = true;
            return;
        }

        // Timed-out while waiting for results
        if (i == 0) {_resultsTxtPane.append(_resBundDisplayMsgs.getString("resultsTimeout") + "\n");}
        _log.warn("Calling InetAddress.getLocalHost() twice");
        try {
            _txtDiagnosis.append(_resBundDisplayMsgs.getString("client") + ": " + InetAddress.getLocalHost() + "\n");
        } catch (SecurityException e) {
            _txtDiagnosis.append(_resBundDisplayMsgs.getString("client") + ": 127.0.0.1\n");
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("unableToObtainIP") + "\n");
            _log.warn("Unable to obtain local IP address: using 127.0.0.1", e);
        }

        // Final cleanup steps after completion of tests
        protocolObj.close();
        ctlSocket.close();
        // call testResults method
        try {testResults(_sTestResults);}
        catch (Exception ex) {
            _resultsTxtPane.append(_resBundDisplayMsgs.getString("resultsParseError") + "\n");
            _resultsTxtPane.append(ex + "\n");
        }

        pub_isReady = "yes";
        pub_errmsg = "All tests completed OK.";
        pub_status = "done";
    }

    /**
     *  Return a SSL or standard socket depending on config
     */
    private Socket newSocket(InetAddress hostAddress, int ctlPort) throws IOException {
        if (_log.shouldInfo()) {
            _log.info("Connecting to " + hostAddress + ':' + ctlPort, new Exception("I did it"));
        }
        Socket rv;
        if (_useSSL) {
            rv = _sslFactory.createSocket(hostAddress, ctlPort);
            if (_log.shouldWarn()) {_log.warn("New SSL socket to " + hostAddress + ':' + ctlPort);}
        } else {
            rv = new Socket();
            rv.connect(new InetSocketAddress(hostAddress, ctlPort), 30*1000);
            if (_log.shouldWarn()) {_log.warn("New non-SSL socket to " + hostAddress + ':' + ctlPort);}
        }
        return rv;
    }

    /**
     * Method that interprets test results. This routine extracts the key-value pairs of results
     * of various categories and assigns these to the correct variables.
     *
     * These values are then interpreted to make decisions about various measurement items and
     * written to the main results, statistics or web100 panels.
     *
     * @param sTestResParam String containing all results
     */
    public void testResults(String sTestResParam) {
        StringTokenizer tokens;
        int i = 0;
        String sSysvar, sStrval;
        int iSysval, iZero = 0;
        double dSysval2, j;
        long lSysval3;
        String sOsName, sOsArch, sOsVer, sJavaVer, sJavaVendor, sClient;

        // extract key-value pair results
        tokens = new StringTokenizer(sTestResParam);
        sSysvar = null;
        sStrval = null;
        _txtDiagnosis.append("=== Results sent by the server ===\n");
        while (tokens.hasMoreTokens()) {
            if (++i % 2 == 1) {sSysvar = tokens.nextToken();}
            else {
                sStrval = tokens.nextToken();
                _txtDiagnosis.append(sSysvar + " " + sStrval + "\n");

                // Check if it's saved anywhere after parsing
                if (isValueSave(sSysvar)) {
                    if (sSysvar.equals("DataBytesOut:")) {
                        try {lSysval3 = Long.parseLong(sStrval);} // long
                        catch (Exception e) {
                            _log.warn("Exception occured reading a web100 var " + sSysvar, e);
                            lSysval3 = -1;
                        }
                        // save value into a key value expected by us
                        save_long_values(sSysvar, lSysval3);
                    }
                    else if (sStrval.indexOf(".") == -1) { // no decimal point, hence
                        // If it fails as an int it's probably too big since the values are often unsigned
                        try {iSysval = Integer.parseInt(sStrval);} // integer
                        catch (Exception e) {
                            _log.warn("Exception occured reading a web100 var " + sSysvar, e);
                            iSysval = -1;
                        }
                        // save value into a key value expected by us
                        save_int_values(sSysvar, iSysval);
                    } else { // if not integer, save as double
                        dSysval2 = Double.valueOf(sStrval).doubleValue();
                        save_dbl_values(sSysvar, dSysval2);
                    }
                }
            }
        }

        // Grab some client details from the Applet environment
        sOsName = System.getProperty("os.name");
        pub_osName = sOsName;

        sOsArch = System.getProperty("os.arch");
        pub_osArch = sOsArch;

        sOsVer = System.getProperty("os.version");
        pub_osVer = sOsVer;

        sJavaVer = System.getProperty("java.version");
        pub_pluginVer = sJavaVer;

        sJavaVendor = System.getProperty("java.vendor");

        if (sOsArch.startsWith("x86") == true) {sClient = _resBundDisplayMsgs.getString("pc");}
        else {sClient = _resBundDisplayMsgs.getString("workstation");}

        // Calculate some variables and determine path conditions.
        // Note: calculations now done in server and the results are shipped back to the client for printing.

        if (_iCountRTT > 0) {

            // Now write some _resBundDisplayMsgs to the screen.
            // Access speed/technology details added to the result main panel.
            // Link speed is also assigned.

            // Try to determine bottleneck link type

            if (_iC2sData < NDTConstants.DATA_RATE_ETHERNET) { // < 3
                if (_iC2sData < NDTConstants.DATA_RATE_RTT) {

                    // Data collected was not sufficient to determine bottleneck type
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("unableToDetectBottleneck") + "\n");
                    pub_AccessTech = "Connection type unknown";

                } else {
                    // get link speed
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("your") + " " + sClient + " " +
                                           _resBundDisplayMsgs.getString("connectedTo") + " ");
                    if (_iC2sData == NDTConstants.DATA_RATE_DIAL_UP) {

                        _resultsTxtPane.append(_resBundDisplayMsgs.getString("dialup") + "\n");
                        mylink = .064; // 64 kbps speed
                        pub_AccessTech = "Dial-up Modem";
                    } else {
                        _resultsTxtPane.append(_resBundDisplayMsgs.getString("cabledsl") + "\n");
                        mylink = 3;
                        pub_AccessTech = "Cable/DSL modem";
                    }
                }
            } else {
                _resultsTxtPane.append(_resBundDisplayMsgs.getString("theSlowestLink") + " ");
                switch (_iC2sData) {
                case NDTConstants.DATA_RATE_ETHERNET:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("10mbps") + "\n");
                    mylink = 10;
                    pub_AccessTech = "10 Mbps Ethernet";
                    break;
                case NDTConstants.DATA_RATE_T3:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("45mbps") + "\n");
                    mylink = 45;
                    pub_AccessTech = "45 Mbps T3/DS3 subnet";
                    break;
                case NDTConstants.DATA_RATE_FAST_ETHERNET:
                    _resultsTxtPane.append("100 Mbps ");
                    mylink = 100;
                    pub_AccessTech = "100 Mbps Ethernet";

                    //Fast Ethernet. Determine if half/full duplex link was found
                    if (half_duplex == 0) {
                        _resultsTxtPane.append(_resBundDisplayMsgs.getString("fullDuplex") + "\n");
                    } else {
                        _resultsTxtPane.append(_resBundDisplayMsgs.getString("halfDuplex") + "\n");
                    }
                    break;
                case NDTConstants.DATA_RATE_OC_12:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("622mbps") + "\n");
                    mylink = 622;
                    pub_AccessTech = "622 Mbps OC-12";
                    break;
                case NDTConstants.DATA_RATE_GIGABIT_ETHERNET:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("1gbps") + "\n");
                    mylink = 1000;
                    pub_AccessTech = "1.0 Gbps Gigabit Ethernet";
                    break;
                case NDTConstants.DATA_RATE_OC_48:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("2.4gbps") + "\n");
                    mylink = 2400;
                    pub_AccessTech = "2.4 Gbps OC-48";
                    break;
                case NDTConstants.DATA_RATE_10G_ETHERNET:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("10gbps") + "\n");
                    mylink = 10000;
                    pub_AccessTech = "10 Gigabit Ethernet/OC-192";
                    break;
                default: // default block indicating no match
                    _log.warn("No _iC2sData option match");
                    break;
                }
            }

            // duplex mismatch
            switch (mismatch) {
                case NDTConstants.DUPLEX_NOK_INDICATOR: //1
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("oldDuplexMismatch") + "\n");
                    break;
                case NDTConstants.DUPLEX_SWITCH_FULL_HOST_HALF:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("duplexFullHalf") + "\n");
                    break;
                case NDTConstants.DUPLEX_SWITCH_HALF_HOST_FULL:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("duplexHalfFull") + "\n");
                    break;
                case NDTConstants.DUPLEX_SWITCH_FULL_HOST_HALF_POSS:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("possibleDuplexFullHalf") + "\n");
                    break;
                case NDTConstants.DUPLEX_SWITCH_HALF_HOST_FULL_POSS:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("possibleDuplexHalfFull") + "\n");
                    break;
                case NDTConstants.DUPLEX_SWITCH_HALF_HOST_FULL_WARN:
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("possibleDuplexHalfFullWarning") + "\n");
                    break;
                case NDTConstants.DUPLEX_OK_INDICATOR:
                    if (bad_cable == 1) {_resultsTxtPane.append(_resBundDisplayMsgs.getString("excessiveErrors") + "\n");}
                    if (congestion == 1) {_resultsTxtPane.append(_resBundDisplayMsgs.getString("otherTraffic") + "\n");}

                /*
                 * We seem to be transmitting less than link speed possibly due a receiver window setting
                 * (i.e calculated bandwidth is greater than measured throughput). Advise appropriate size.
                 *
                 * Note: All comparisons henceforth of ((window size * 2/rttsec) < mylink) are along the same logic.
                 */

                if (((2 * rwin) / rttsec) < mylink) { // multiply by 2 to counter round-trip

                    // Link speed is in Mbps. Convert it back to kbps (*1000), and bytes (/8)
                    j = (float) ((mylink * avgrtt) * NDTConstants.KILO) / NDTConstants.EIGHT / NDTConstants.KILO_BITS;
                    if (j > (float) _iMaxRwinRcvd) {
                        _resultsTxtPane.append(_resBundDisplayMsgs.getString("receiveBufferShouldBe") + " " + NDTUtils.prtdbl(j) +
                                               _resBundDisplayMsgs.getString("toMaximizeThroughput") + " \n");
                    }
                }
                break;

                default: // default for indication of no match for mismatch variable
                break;
            }

            // C2S throughput test: Packet queuing
            if ((_yTests & NDTConstants.TEST_C2S) == NDTConstants.TEST_C2S) {
                if (_dSc2sspd < (_dC2sspd * (1.0 - NDTConstants.VIEW_DIFF))) {
                    JLabel info = new JLabel(_resBundDisplayMsgs.getString("information"));
                    info.setForeground(Color.BLUE);
                    info.setCursor(new Cursor(Cursor.HAND_CURSOR));
                    info.setAlignmentY((float) 0.8);
                    _resultsTxtPane.insertComponent(info);
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("c2sPacketQueuingDetected") + "\n");
                }
            }

            // S2C throughput test: Packet queuing
            if ((_yTests & NDTConstants.TEST_S2C) == NDTConstants.TEST_S2C) {
                if (_dS2cspd < (_dSs2cspd * (1.0 - NDTConstants.VIEW_DIFF))) {
                    JLabel info = new JLabel(_resBundDisplayMsgs.getString("information"));
                    info.setForeground(Color.BLUE);
                    info.setCursor(new Cursor(Cursor.HAND_CURSOR));
                    info.setAlignmentY((float) 0.8);
                    _resultsTxtPane.insertComponent(info);
                    _resultsTxtPane.append(_resBundDisplayMsgs.getString("s2cPacketQueuingDetected") + "\n");
                }
            } // end s2C test based packet queuing results

            // Add client information obtained earlier
            _txtStatistics.append("\n\t------  " + _resBundDisplayMsgs.getString("clientInfo") + "------\n");
            _txtStatistics.append(_resBundDisplayMsgs.getString("osData") + " " + _resBundDisplayMsgs.getString("name") + " = " + sOsName +
                                  ", " + _resBundDisplayMsgs.getString("architecture") + " = " + sOsArch);
            _txtStatistics.append(", " + _resBundDisplayMsgs.getString("version") + " = " + sOsVer + "\n");
            _txtStatistics.append(_resBundDisplayMsgs.getString("javaData") + ": " + _resBundDisplayMsgs.getString("vendor") + " = " +
                                  sJavaVendor + ", " + _resBundDisplayMsgs.getString("version") + " = " + sJavaVer + "\n");
            _txtStatistics.append("\n\t------  " + _resBundDisplayMsgs.getString(_sServerType + "Details") + "  ------\n");

            /*
             * Now add data to the statistics pane about access speed/technology.
             * Slightly different from the earlier switch (that added data about this to the Results pane)
             * in that negative values are checked for too.
             */
            switch (_iC2sData) {
            case NDTConstants.DATA_RATE_INSUFFICIENT_DATA:
                _txtStatistics.append(_resBundDisplayMsgs.getString("insufficient") + "\n");
                break;
            case NDTConstants.DATA_RATE_SYSTEM_FAULT:
                _txtStatistics.append(_resBundDisplayMsgs.getString("ipcFail") + "\n");
                break;
            case NDTConstants.DATA_RATE_RTT:
                _txtStatistics.append(_resBundDisplayMsgs.getString("rttFail") + "\n");
                break;
            case NDTConstants.DATA_RATE_DIAL_UP:
                _txtStatistics.append(_resBundDisplayMsgs.getString("foundDialup") + "\n");
                break;
            case NDTConstants.DATA_RATE_T1:
                _txtStatistics.append(_resBundDisplayMsgs.getString("foundDsl") + "\n");
                break;
            case NDTConstants.DATA_RATE_ETHERNET:
                _txtStatistics.append(_resBundDisplayMsgs.getString("found10mbps") + "\n");
                break;
            case NDTConstants.DATA_RATE_T3:
                _txtStatistics.append(_resBundDisplayMsgs.getString("found45mbps") + "\n");
                break;
            case NDTConstants.DATA_RATE_FAST_ETHERNET:
                _txtStatistics.append(_resBundDisplayMsgs.getString("found100mbps") + "\n");
                break;
            case NDTConstants.DATA_RATE_OC_12:
                _txtStatistics.append(_resBundDisplayMsgs.getString("found622mbps") + "\n");
                break;
            case NDTConstants.DATA_RATE_GIGABIT_ETHERNET:
                _txtStatistics.append(_resBundDisplayMsgs.getString("found1gbps") + "\n");
                break;
            case NDTConstants.DATA_RATE_OC_48:
                _txtStatistics.append(_resBundDisplayMsgs.getString("found2.4gbps") + "\n");
                break;
            case NDTConstants.DATA_RATE_10G_ETHERNET:
                _txtStatistics.append(_resBundDisplayMsgs.getString("found10gbps") + "\n");
                break;
            }

            // Add decisions about duplex mode, congestion, duplex mismatch to Statistics pane
            if (half_duplex == NDTConstants.DUPLEX_OK_INDICATOR) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("linkFullDpx") + "\n");
            } else {_txtStatistics.append(_resBundDisplayMsgs.getString("linkHalfDpx") + "\n");}

            if (congestion == NDTConstants.CONGESTION_NONE) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("congestNo") + "\n");
            } else {_txtStatistics.append(_resBundDisplayMsgs.getString("congestYes") + "\n");}

            if (bad_cable == NDTConstants.CABLE_STATUS_OK) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("cablesOk") + "\n");
            } else {_txtStatistics.append(_resBundDisplayMsgs.getString("cablesNok") + "\n");}

            if (mismatch == NDTConstants.DUPLEX_OK_INDICATOR) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("duplexOk") + "\n");
            } else if (mismatch == NDTConstants.DUPLEX_NOK_INDICATOR) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("duplexNok") + " ");
            } else if (mismatch == NDTConstants.DUPLEX_SWITCH_FULL_HOST_HALF) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("duplexFullHalf") + "\n");
            } else if (mismatch == NDTConstants.DUPLEX_SWITCH_HALF_HOST_FULL) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("duplexHalfFull") + "\n");
            }

            _txtStatistics.append("\n" + _resBundDisplayMsgs.getString(_sServerType + "rtt") + " =  " +
                                  NDTUtils.prtdbl(avgrtt) + " " + "ms" + "; ");

            _txtStatistics.append(_resBundDisplayMsgs.getString("packetsize") +" = " + _iCurrentMSS + " " +
                                  _resBundDisplayMsgs.getString("bytes") + "; " +
                                  _resBundDisplayMsgs.getString("and") + " \n");

            // Check packet retransmissions count, and update Statistics pane
            if (_iPktsRetrans > 0) { // packet retransmissions found
                _txtStatistics.append(_iPktsRetrans + " " + _resBundDisplayMsgs.getString("pktsRetrans"));
                _txtStatistics.append(", " + _iDupAcksIn + " " + _resBundDisplayMsgs.getString("dupAcksIn"));
                _txtStatistics.append(", " + _resBundDisplayMsgs.getString("and") + " " + _iSACKsRcvd + " " +
                                      _resBundDisplayMsgs.getString("sackReceived") + "\n");
                if (_iTimeouts > 0) {
                    _txtStatistics.append(_resBundDisplayMsgs.getString("connStalled") + " " + _iTimeouts + " " +
                                          _resBundDisplayMsgs.getString("timesPktLoss") + "\n");
                }

                _txtStatistics.append(_resBundDisplayMsgs.getString("connIdle") + " " + NDTUtils.prtdbl(waitsec) + " " +
                                      _resBundDisplayMsgs.getString("seconds") + " (" +
                                      NDTUtils.prtdbl((waitsec / timesec) * NDTConstants.PERCENTAGE) +
                                      _resBundDisplayMsgs.getString("pctOfTime") + ")\n");
            } else if (_iDupAcksIn > 0) { // No packet loss, but packets arrived out-of-order
                _txtStatistics.append(_resBundDisplayMsgs.getString("noPktLoss1") + " - ");
                _txtStatistics.append(_resBundDisplayMsgs.getString("ooOrder") + " " + NDTUtils.prtdbl(order * NDTConstants.PERCENTAGE) +
                                      _resBundDisplayMsgs.getString("pctOfTime") + "\n");
            } else { // No packets transmissions found
                _txtStatistics.append(_resBundDisplayMsgs.getString("noPktLoss2") + ".\n");
            }

            // Add Packet queuing details found during C2S throughput test to
            // the statistics pane. Data is displayed as a percentage
            if ((_yTests & NDTConstants.TEST_C2S) == NDTConstants.TEST_C2S) {
                if (_dC2sspd > _dSc2sspd) {
                    if (_dSc2sspd < (_dC2sspd * (1.0 - NDTConstants.VIEW_DIFF))) {
                        _txtStatistics.append(_resBundDisplayMsgs.getString("c2s") + " " +
                                              _resBundDisplayMsgs.getString("eqSeen") + ": " +
                                              NDTUtils.prtdbl(NDTConstants.PERCENTAGE * (_dC2sspd - _dSc2sspd) / _dC2sspd) + "%\n");
                    } else {
                        _txtStatistics.append(_resBundDisplayMsgs.getString("c2s") + " " +
                                              _resBundDisplayMsgs.getString("qSeen") + ": " +
                                              NDTUtils.prtdbl(NDTConstants.PERCENTAGE * (_dC2sspd - _dSc2sspd) / _dC2sspd) + "%\n");
                    }
                }
            }

            // Add Packet queuing details found during S2C throughput test to
            // the statistics pane. Data is displayed as a percentage
            if ((_yTests & NDTConstants.TEST_S2C) == NDTConstants.TEST_S2C) {
                if (_dSs2cspd > _dS2cspd) {
                    if (_dSs2cspd < (_dSs2cspd * (1.0 - NDTConstants.VIEW_DIFF))) {
                        _txtStatistics.append(_resBundDisplayMsgs.getString("s2c") + " " +
                                              _resBundDisplayMsgs.getString("eqSeen") + ": " +
                                              NDTUtils.prtdbl(NDTConstants.PERCENTAGE * (_dSs2cspd - _dS2cspd) / _dSs2cspd) + "%\n");
                    } else {
                        _txtStatistics.append(_resBundDisplayMsgs.getString("s2c") + " " +
                                              _resBundDisplayMsgs.getString("qSeen") + ": " +
                                              NDTUtils.prtdbl(NDTConstants.PERCENTAGE * (_dSs2cspd - _dS2cspd) / _dSs2cspd) + "%\n");
                    }
                }
            }

            // Add connection details to statistics pane
            // Is the connection receiver limited?
            if (rwintime > NDTConstants.BUFFER_LIMITED) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("thisConnIs") + " " +
                                      _resBundDisplayMsgs.getString("limitRx") + " " +
                                      NDTUtils.prtdbl(rwintime * NDTConstants.PERCENTAGE) +
                                      _resBundDisplayMsgs.getString("pctOfTime") + ".\n");
                pub_pctRcvrLimited = rwintime * NDTConstants.PERCENTAGE;

                // I think there is a bug here, it sometimes tells you to increase the buffer
                // size, but the new size is smaller than the current - (older comment left as is)
                if (((2 * rwin) / rttsec) < mylink) { //// multiply by 2 to counter round-trip
                    _txtStatistics.append("  " + _resBundDisplayMsgs.getString("incrRxBuf") + " (" +
                                          NDTUtils.prtdbl(_iMaxRwinRcvd / NDTConstants.KILO_BITS) + " KB) " +
                                          _resBundDisplayMsgs.getString("willImprove") + "\n");
                }
            }

            // Is the connection sender limited?
            if (sendtime > NDTConstants.BUFFER_LIMITED) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("thisConnIs") + " " +
                                      _resBundDisplayMsgs.getString("limitTx") + " " +
                                      NDTUtils.prtdbl(sendtime * NDTConstants.PERCENTAGE) +
                                      _resBundDisplayMsgs.getString("pctOfTime") + ".\n");

                if ((2 * (swin / rttsec)) < mylink) {
                    // divide by 2 to counter round-trip
                    _txtStatistics.append("  " + _resBundDisplayMsgs.getString("incrTxBuf") + " (" +
                                          NDTUtils.prtdbl(_iSndbuf / (2 * NDTConstants.KILO_BITS)) + " KB) " +
                                          _resBundDisplayMsgs.getString("willImprove") + "\n");
                }
            }

            // Is the connection network limited?
            // If the congestion window is limited more than 0.5% of the time, NDT claims the connection is network limited.
            if (cwndtime > .005) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("thisConnIs") + " " +
                                      _resBundDisplayMsgs.getString("limitNet") + " " +
                                      NDTUtils.prtdbl(cwndtime * NDTConstants.PERCENTAGE) +
                                      _resBundDisplayMsgs.getString("pctOfTime") + ".\n");
            }

            // Is the loss excessive?
            // If the link speed is less than a T3, and loss is greater than 1 percent, loss is determined to be excessive.
            if ((spd < 4) && (loss > .01)) {_txtStatistics.append(_resBundDisplayMsgs.getString("excLoss") + "\n");}

            // Update statistics on TCP negotiated optional Performance Settings
            _txtStatistics.append("\n" + _resBundDisplayMsgs.getString(_sServerType + "tcpOpts") + " \n");
            _txtStatistics.append("RFC 2018 Selective Acknowledgment: ");
            if (_iSACKEnabled != 0) {_txtStatistics.append(_resBundDisplayMsgs.getString("on") + "\n");}
            else {_txtStatistics.append(_resBundDisplayMsgs.getString("off") + "\n");}

            _txtStatistics.append("RFC 896 Nagle Algorithm: ");
            if (_iNagleEnabled != 0) {_txtStatistics.append(_resBundDisplayMsgs.getString("on") + "\n");}
            else {_txtStatistics.append(_resBundDisplayMsgs.getString("off") + "\n");}

            _txtStatistics.append("RFC 3168 Explicit Congestion Notification: ");
            if (_iECNEnabled != 0) {_txtStatistics.append(_resBundDisplayMsgs.getString("on") + "\n");}
            else {_txtStatistics.append(_resBundDisplayMsgs.getString("off") + "\n");}

            _txtStatistics.append("RFC 1323 Time Stamping: ");
            if (_iTimestampsEnabled != 0) {_txtStatistics.append(_resBundDisplayMsgs.getString("on") + "\n");}
            else {_txtStatistics.append(_resBundDisplayMsgs.getString("off") + "\n");}

            /*
             * Max rec window size lesser than TCP's max value, so, no scaling requested.
             * According to RFC1323, Section 2.3 the max valid value of _iWinScaleRcvd is 14.
             * Unclear why NDT uses 20 for this, but leaving for now in case this is a web100
             * error value of some kind. (Revisit after Methodology document written.)
             */
            _txtStatistics.append("RFC 1323 Window Scaling: ");
            if (_iMaxRwinRcvd < NDTConstants.TCP_MAX_RECV_WIN_SIZE) {_iWinScaleRcvd = 0;}
            if ((_iWinScaleRcvd == 0) || (_iWinScaleRcvd > 20)) {
                _txtStatistics.append(_resBundDisplayMsgs.getString("off") + "\n");
            } else {
                _txtStatistics.append(_resBundDisplayMsgs.getString("on") + "; " +
                                      _resBundDisplayMsgs.getString("scalingFactors") + " -  " +
                                      _resBundDisplayMsgs.getString("server") + "=" +
                                      _iWinScaleRcvd + ", " + _resBundDisplayMsgs.getString("client") + "=" +
                                      _iWinScaleSent + "\n");
            }
            _txtStatistics.append("\n");
            // End tcp negotiated performance settings

            _txtDiagnosis.append("\n");

        } // end if (CountRTT >0)

    } // end testResults()

    /**
     * Pop up a window to display some information about TCP packet queuing.
     */
    public void showBufferedBytesInfo() {
        JOptionPane.showMessageDialog(null,
                _resBundDisplayMsgs.getString("packetQueuingInfo"),
                _resBundDisplayMsgs.getString("packetQueuing"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /*
     * This routine saves the specific value into the variable of the same name.
     * There should probably be an easier way to do this.
     */

    /**
     * Method to save double values of various "keys" from the the test results
     * string into corresponding double datatypes.
     *
     * @param sSysvarParam key name string
     * @param dSysvalParam Value for this key name
     */
    public void save_dbl_values(String sSysvarParam, double dSysvalParam) {
        if (sSysvarParam.equals("bw:")) {estimate = dSysvalParam;}
        else if (sSysvarParam.equals("loss:")) {
            loss = dSysvalParam;
            pub_loss = loss;
        } else if (sSysvarParam.equals("avgrtt:")) {
            avgrtt = dSysvalParam;
            pub_avgrtt = avgrtt;
        } else if (sSysvarParam.equals("waitsec:")) {waitsec = dSysvalParam;}
        else if (sSysvarParam.equals("timesec:")) {timesec = dSysvalParam;}
        else if (sSysvarParam.equals("order:")) {order = dSysvalParam;}
        else if (sSysvarParam.equals("rwintime:")) {rwintime = dSysvalParam;}
        else if (sSysvarParam.equals("sendtime:")) {sendtime = dSysvalParam;}
        else if (sSysvarParam.equals("cwndtime:")) {
            cwndtime = dSysvalParam;
            pub_cwndtime = cwndtime;
        } else if (sSysvarParam.equals("rttsec:")) {rttsec = dSysvalParam;}
        else if (sSysvarParam.equals("rwin:")) {rwin = dSysvalParam;}
        else if (sSysvarParam.equals("swin:")) {swin = dSysvalParam;}
        else if (sSysvarParam.equals("cwin:")) {cwin = dSysvalParam;}
        else if (sSysvarParam.equals("spd:")) {spd = dSysvalParam;}
        else if (sSysvarParam.equals("aspd:")) {aspd = dSysvalParam;}
    } // save_dbl_values()

    /**
     * Method to save long values of various "keys" from the the test results
     * string into corresponding long datatypes.
     *
     * @param sSysvarParam key name string
     * @param lSysvalParam Value for this key name
     */
    public void save_long_values(String sSysvarParam, long lSysvalParam) {
        if (sSysvarParam.equals("DataBytesOut:")) {_iDataBytesOut = lSysvalParam;}
    }

    /**
     * Method to save integer values of various "keys" from the the test results
     * string into corresponding integer datatypes.
     *
     * @param sSysvarParam String key name
     * @param iSysvalParam Value for this key name
     *
     */
    public void save_int_values(String sSysvarParam, int iSysvalParam) {
        //
        // Values saved for interpretation: SumRTT CountRTT CurrentMSS Timeouts
        // PktsRetrans SACKsRcvd DupAcksIn MaxRwinRcvd MaxRwinSent Sndbuf Rcvbuf
        // DataPktsOut SndLimTimeRwin SndLimTimeCwnd SndLimTimeSender
        //
        if (sSysvarParam.equals("MSSSent:")) {MSSSent = iSysvalParam;}
        else if (sSysvarParam.equals("MSSRcvd:")) {MSSRcvd = iSysvalParam;}
        else if (sSysvarParam.equals("ECNEnabled:")) {_iECNEnabled = iSysvalParam;}
        else if (sSysvarParam.equals("NagleEnabled:")) {_iNagleEnabled = iSysvalParam;}
        else if (sSysvarParam.equals("SACKEnabled:")) {_iSACKEnabled = iSysvalParam;}
        else if (sSysvarParam.equals("TimestampsEnabled:")) {_iTimestampsEnabled = iSysvalParam;}
        else if (sSysvarParam.equals("WinScaleRcvd:")) {_iWinScaleRcvd = iSysvalParam;}
        else if (sSysvarParam.equals("WinScaleSent:")) {_iWinScaleSent = iSysvalParam;}
        else if (sSysvarParam.equals("SumRTT:")) {_iSumRTT = iSysvalParam;}
        else if (sSysvarParam.equals("CountRTT:")) {_iCountRTT = iSysvalParam;}
        else if (sSysvarParam.equals("CurMSS:")) {_iCurrentMSS = iSysvalParam;}
        else if (sSysvarParam.equals("Timeouts:")) {_iTimeouts = iSysvalParam;}
        else if (sSysvarParam.equals("PktsRetrans:")) {_iPktsRetrans = iSysvalParam;}
        else if (sSysvarParam.equals("SACKsRcvd:")) {
            _iSACKsRcvd = iSysvalParam;
            pub_SACKsRcvd = _iSACKsRcvd;
        } else if (sSysvarParam.equals("DupAcksIn:")) {
            _iDupAcksIn = iSysvalParam;
            pub_DupAcksIn = _iDupAcksIn;
        } else if (sSysvarParam.equals("MaxRwinRcvd:")) {
            _iMaxRwinRcvd = iSysvalParam;
            pub_MaxRwinRcvd = _iMaxRwinRcvd;
        } else if (sSysvarParam.equals("MaxRwinSent:")) {_iMaxRwinSent = iSysvalParam;}
        else if (sSysvarParam.equals("Sndbuf:")) {_iSndbuf = iSysvalParam;}
        else if (sSysvarParam.equals("X_Rcvbuf:")) {_iRcvbuf = iSysvalParam;}
        else if (sSysvarParam.equals("DataPktsOut:")) {_iDataPktsOut = iSysvalParam;}
        else if (sSysvarParam.equals("FastRetran:")) {_iFastRetran = iSysvalParam;}
        else if (sSysvarParam.equals("AckPktsOut:")) {_iAckPktsOut = iSysvalParam;}
        else if (sSysvarParam.equals("SmoothedRTT:")) {_iSmoothedRTT = iSysvalParam;}
        else if (sSysvarParam.equals("CurCwnd:")) {_iCurrentCwnd = iSysvalParam;}
        else if (sSysvarParam.equals("MaxCwnd:")) {_iMaxCwnd = iSysvalParam;}
        else if (sSysvarParam.equals("SndLimTimeRwin:")) {_iSndLimTimeRwin = iSysvalParam;}
        else if (sSysvarParam.equals("SndLimTimeCwnd:")) {_iSndLimTimeCwnd = iSysvalParam;}
        else if (sSysvarParam.equals("SndLimTimeSender:")) {_iSndLimTimeSender = iSysvalParam;}
        else if (sSysvarParam.equals("AckPktsIn:")) {_iAckPktsIn = iSysvalParam;}
        else if (sSysvarParam.equals("SndLimTransRwin:")) {_iSndLimTransRwin = iSysvalParam;}
        else if (sSysvarParam.equals("SndLimTransCwnd:")) {_iSndLimTransCwnd = iSysvalParam;}
        else if (sSysvarParam.equals("SndLimTransSender:")) {_iSndLimTransSender = iSysvalParam;}
        else if (sSysvarParam.equals("MaxSsthresh:")) {_iMaxSsthresh = iSysvalParam;}
        else if (sSysvarParam.equals("CurRTO:")) {
            _iCurrentRTO = iSysvalParam;
            pub_CurRTO = _iCurrentRTO;
        } else if (sSysvarParam.equals("MaxRTO:")) {pub_MaxRTO = iSysvalParam;}
        else if (sSysvarParam.equals("MinRTO:")) {pub_MinRTO = iSysvalParam;}
        else if (sSysvarParam.equals("MinRTT:")) {pub_MinRTT = iSysvalParam;}
        else if (sSysvarParam.equals("MaxRTT:")) {pub_MaxRTT = iSysvalParam;}
        else if (sSysvarParam.equals("CurRwinRcvd:")) {pub_CurRwinRcvd = iSysvalParam;}
        else if (sSysvarParam.equals("Timeouts:")) {pub_Timeouts = iSysvalParam;}
        else if (sSysvarParam.equals("c2sData:")) {_iC2sData = iSysvalParam;}
        else if (sSysvarParam.equals("c2sAck:")) {_iC2sAck = iSysvalParam;}
        else if (sSysvarParam.equals("s2cData:")) {_iS2cData = iSysvalParam;}
        else if (sSysvarParam.equals("s2cAck:")) {_iS2cAck = iSysvalParam;}
        else if (sSysvarParam.equals("PktsOut:")) {_iPktsOut = iSysvalParam;}
        else if (sSysvarParam.equals("mismatch:")) {
            mismatch = iSysvalParam;
            pub_mismatch = mismatch;
        } else if (sSysvarParam.equals("congestion:")) {
            congestion = iSysvalParam;
            pub_congestion = congestion;
        } else if (sSysvarParam.equals("bad_cable:")) {
            bad_cable = iSysvalParam;
            pub_Bad_cable = bad_cable;
        } else if (sSysvarParam.equals("half_duplex:")) {half_duplex = iSysvalParam;}
        else if (sSysvarParam.equals("CongestionSignals:")) {_iCongestionSignals = iSysvalParam;}
        else if (sSysvarParam.equals("RcvWinScale:")) {
            if (_iRcvWinScale > 15) {_iRcvWinScale = 0;}
            else {_iRcvWinScale = iSysvalParam;}
        }
    } // save_int_values()

    /**
     * Method check, if values of various "keys" from the the test results string is saved by
     * save_int_values(), save_dbl_values() or save_long_values() after parse to int/double/long
     *
     * @param sSysvarParam String key name
     */
    private boolean isValueSave(String sSysvarParam) {
        Set<String> saveValues = new HashSet<>(Arrays.asList(
            "AckPktsIn:", "AckPktsOut:", "Bad_cable:", "Congestion:", "CongestionSignals:", "CountRTT:", "CurCwnd:",
            "CurMSS:", "CurRTO:", "CurRwinRcvd:", "DataBytesOut:", "DataPktsOut:", "DupAcksIn:", "ECNEnabled:",
            "FastRetran:", "Half_duplex:", "MaxCwnd:", "MaxRTO:", "MaxRwinRcvd:", "MaxRwinSent:", "MaxSsthresh:",
            "MaxRTT:", "MinRTO:", "MinRTT:", "MSSRcvd:", "MSSSent:", "NagleEnabled:", "Order:", "PktsOut:",
            "PktsRetrans:", "RcvWinScale:", "Rwin:", "S2cAck:", "S2cData:", "SACKEnabled:", "SACKsRcvd:", "Sndbuf:",
            "SndLimTimeCwnd:", "SndLimTimeRwin:", "SndLimTimeSender:", "SndLimTransCwnd:", "SndLimTransRwin:",
            "SndLimTransSender:", "SmoothedRTT:", "Sndbuf:", "Swin:", "SumRTT:", "Swin:", "Timeouts:", "TimestampsEnabled:",
            "Timesec:", "Waitsec:", "WinScaleRcvd:", "WinScaleSent:", "X_Rcvbuf:", "aspd:", "avgrtt:", "bw:", "c2sAck:",
            "c2sData:", "cwin:", "cwndtime:", "loss:", "mismatch:", "rttsec:", "rwintime:", "sendtime:", "spd:"
        ));
        return saveValues.contains(sSysvarParam);
    }

    /**
     * Utility method to get parameter value.
     *
     * @param paramStrName Key String whose value has to be found
     * @return String Value of key requested for
     */
    public String getParameter(String paramStrName) {
        if (!_bIsApplication) {return super.getParameter(paramStrName);}
        return null;
    }

    private void setsHostName(String sHostName) {
        this.sHostName = sHostName;
        InetAddress[] addresses;
        InetAddress found = null;

        try {
            addresses = InetAddress.getAllByName(sHostName);

            if (_chkboxPreferIPv6.isSelected()) {
                for(int k = 0; k < addresses.length; ++k) {
                    if (addresses[k] instanceof Inet6Address) {
                        found = addresses[k];
                        break;
                    }
                }
            }
        } catch (Exception e) {throw new IllegalArgumentException(e);}

        if (null != found) {this.hostAddress = found;}
        else if (addresses.length > 0) {this.hostAddress = addresses[0];}
    }

    /**
     * Function that returns a variable corresponding to the parameter passed to it as a request.
     * @param varName {String} The parameter which the caller is seeking.
     * @return {String} The value of the desired parameter.
     */
    public String getNDTvar(String varName) {
        if (varName.equals("ACCESS_TECH")) {return get_AccessTech();}
        else if (varName.equals("ClientToServerSpeed")) {return get_c2sspd();}
        else if (varName.equals("CONGESTION")) {return get_congestion();}
        else if (varName.equals("CURRTO")) {return get_CurRTO();}
        else if (varName.equals("CURRWINRCVD")) {return get_CurRwinRcvd();}
        else if (varName.equals("DUPACKSIN")) {return get_DupAcksIn();}
        else if (varName.equals("Jitter")) {return get_jitter();}
        else if (varName.equals("LOSS")) {return get_loss();}
        else if (varName.equals("MAXRWINRCVD")) {return get_MaxRwinRcvd();}
        else if (varName.equals("MAXRTT")) {return get_MaxRTT();}
        else if (varName.equals("MINRTT")) {return get_Ping();}
        else if (varName.equals("MISMATCH")) {return get_mismatch();}
        else if (varName.equals("OPTRCVRBUFF")) {return get_optimalRcvrBuffer();}
        else if (varName.equals("OsArchitecture")) {return get_osArch();}
        else if (varName.equals("OperatingSystem")) {return get_osName() + " " + get_osVer();}
        else if (varName.equals("PLUGINVERSION")) {return get_pluginVer();}
        else if (varName.equals("RWINTIME")) {return get_rcvrLimiting();}
        else if (varName.equals("SACKSRCVD")) {return get_SACKsRcvd();}
        else if (varName.equals("SERVERTOCLIENTSPEED")) {return get_s2cspd();}
        else if (varName.equals("WAITSEC")) {return get_WaitSec();}
        else if (varName.equals(NDTConstants.AVGRTT)) {return get_avgrtt();}
        else if (varName.equals(NDTConstants.BAD_CABLE)) {return get_Bad_cable();}
        else if (varName.equals(NDTConstants.CWNDTIME)) {return get_cwndtime();}
        else {return null;}
    }

    /**
     * Function that parse String to Integer
     * @param {String} Value to parse.
     * @return {int} The parsed value.
     */
    private int parseMsgBodyToInt(String msg) {return parseMsgBodyToInt(msg, 10);}

    /**
     * Function that parse String to Integer
     * @param {String} Value to parse.
     * @param {int}  the radix to be used while parsing
     * @return {int} The parsed value.
     */
    private int parseMsgBodyToInt(String msg, int radix) {
        try {
            if (jsonSupport) {return Integer.parseInt(JSONUtils.getSingleMessage(msg), radix);}
            else {return Integer.parseInt(msg, radix);}
        } catch (NumberFormatException nfe) {
            _log.warn("parse input: \"" + msg + '"', nfe);
            return 0;
        }
    }

    /** bigly */
    private ThreadGroup _thread_group;

    /**
     * bigly -- must have been started with main() or runIt()
     */
    @SuppressWarnings("deprecation")
    public void killIt() {
        final ThreadGroup thread_group;
        synchronized(this) {
            thread_group = _thread_group;
            if (thread_group == null) {
                _log.warn("No thread group to kill");
                return;
            }
            // so wantToStop() returns true
            if (_sPanel != null) {_sPanel.endTest();}
        }
        _log.warn("killIt()");
        boolean destroyed = false;
        for (int j = 0; j < 10 && !thread_group.isDestroyed(); j++) {
            Thread[] threads = new Thread[thread_group.activeCount()];
            thread_group.enumerate(threads);
            int done = 0;
            for (int i=0;i<threads.length;i++){
                Thread t = threads[i];
                if (t != null) {
                    if (_log.shouldWarn()) {_log.warn("Interrupting TG thread " + t);}
                    done++;
                    try {t.interrupt();}
                    catch (RuntimeException re) {_log.debug("TG", re);}
                    try {Thread.sleep(20);}
                    catch (InterruptedException ie) {}
                    if (t.isAlive()) {
                        if (_log.shouldWarn()) {_log.warn("Killing TG thread " + t);}
                        try {t.stop();}
                        catch (RuntimeException re) {_log.debug("TG", re);}
                    }
                }
            }

            if (done == 0) {
                _log.warn("TG destroy");
                try {
                    thread_group.destroy();
                    break;
                } catch(Throwable e) {_log.debug("TG", e);}
            }

            try {Thread.sleep(50);}
            catch (InterruptedException ie) {}
        }
    }

    /** bigly */
    public synchronized void runIt() {
        _thread_group = new
        ThreadGroup("NDT") {
            @Override public void uncaughtException(Thread t, Throwable e) {
                _log.warn("Bandwidth test error", e);
            }
        };

        _thread_group.setDaemon(true);
        Thread t = new I2PAppThread(_thread_group, new Runnable() {
            @Override
            public void run() {
                try {new TestWorker().run();}
                catch(Throwable e) {
                    if (!(e instanceof ThreadDeath)) {_log.warn("Bandwidth test error", e);}
                } finally {}
            }
        }, "TestWorker");
        t.setDaemon(true);
        t.start();
    }

    /**
     *  @since 0.9.46 to replace Java Timer and TimerTask
     */
    private abstract class PeriodicTimer extends SimpleTimer2.TimedEvent {
        public PeriodicTimer() {super(_context.simpleTimer2(), 100);}
    }

}