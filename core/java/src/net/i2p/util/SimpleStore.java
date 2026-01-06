/*
 * This is free software, do as you please.
 */

package net.i2p.util;

/**
 *  Deprecated - used only by SimpleTimer
 *
 * @author sponge
 */
public class SimpleStore {

    private boolean answer;

    SimpleStore(boolean x) {
        answer=x;
    }

    /**
      * set the answer
      */
    public void setAnswer(boolean x) {
        answer = x;
    }
    /**
     * Returns the stored answer.
     * @return boolean
     */
    public boolean getAnswer() {
        return answer;
    }

}
