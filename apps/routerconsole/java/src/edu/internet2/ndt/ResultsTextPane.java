package edu.internet2.ndt;

import com.vuze.plugins.mlab.tools.ndt.swingemu.*;

/**
 * Text pane component used as the chief results window that summarizes test results.
 * This class is declared separately to allow easy extension for customization.
 */
public class ResultsTextPane extends JTextPane {

    /**
     * Compiler auto-generate value not directly related to class functionality
     */
    private static final long serialVersionUID = -2224271202004876654L;

    /**
     * Method to append String into the current document
     *
     * @param paramTextStr
     *            String to be inserted into the document
     **/
    public void append(String paramTextStr) {
        try {
            getStyledDocument().insertString(getStyledDocument().getLength(),paramTextStr, null);
        } catch (BadLocationException e) {}
    }

    /**
     * JTextPane method to insert a component into the document as a replacement
     * for currently selected content. If no selection is made, the the
     * component is inserted at the current position of the caret.
     *
     * @param paramCompObj the component to insert
     *
     */
    public void insertComponent(Component paramCompObj) {}

}