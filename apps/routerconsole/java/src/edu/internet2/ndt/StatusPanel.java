package edu.internet2.ndt;

import com.vuze.plugins.mlab.tools.ndt.swingemu.*;

/**
 * Class that displays status of tests being run. It also provides methods to
 * set status message, record intention to stop tests, and to fetch the status
 * of whether the test is to be stopped.
 * */

public class StatusPanel extends JPanel {
    /**
     * Compiler generated constant that is not related to current classes'
     * specific functionality
     */
    private static final long serialVersionUID = 2609233901130079136L;

    private int _iTestsCompleted;
    private int _iTestsNum;
    private boolean _bStop = false;

    /*
     * Constructor
     *
     * @param testsNum Total number of tests scheduled to be run
     *
     * @param sParamaEnableMultiple String indicating whether multiple tests
     * have been scheduled
     */
    public StatusPanel(int iParamTestsNum, String sParamEnableMultiple) {}

    /**
     * Set Test number being run
     *
     */
    private void setTestNoLabelText() {}

    /**
     * Get intention to stop tests
     *
     * @return boolean indicating intention to stop or not
     *
     */
    public synchronized boolean wantToStop() {return _bStop;}

    /**
     * End the currently running test
     * Cannot be restarted.
     *
     */
    public synchronized void endTest() {_bStop = true;}

    /**
     * Sets a string explaining progress of tests
     *
     * @param sParamText String status of test-run
     *
     */
    public void setText(String sParamText) {}

}