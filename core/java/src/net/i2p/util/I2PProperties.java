package net.i2p.util;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This class contains a number of properties ((key,value)-pairs).
 * Additionally, it adds the possibility for callbacks,
 * to allow immediate response to changing properties.
 *
 * @author Mathiasdm
 *
 */
@SuppressWarnings("java:S2975")
public class I2PProperties extends Properties {

    /**
     * Keep a list of callbacks to contact the interested parties
     * that want to know about property changes.
     */
    private final List<I2PPropertyCallback> _callbacks = new CopyOnWriteArrayList<>();

    /**
     * clone.
     */
    @Override
    public Object clone() {
        return super.clone();
    }

    /**
     * I2PProperties.
     */
    public I2PProperties() {
        super();
    }

    /**
     * I2PProperties.
     */
    public I2PProperties(Properties defaults) {
        super(defaults);
    }

    /**
     * addCallBack.
     */
    public void addCallBack(I2PPropertyCallback callback) {
        _callbacks.add(callback);
    }

    /**
     * removeCallBack.
     */
    public void removeCallBack(I2PPropertyCallback callback) {
        _callbacks.remove(callback);
    }

    /**
     * setProperty.
     */
    @Override
    public Object setProperty(String key, String value) {
        Object returnValue = super.setProperty(key, value);
        for (I2PPropertyCallback callback : _callbacks) {
            callback.propertyChanged(key, value);
        }
        return returnValue;
    }

    /**
     * Callback interface for property change notifications.
     *
     * @since 0.9.35
     */
    public interface I2PPropertyCallback {

        /**
         * value).
         */
        public void propertyChanged(String key, String value);
    }
}
