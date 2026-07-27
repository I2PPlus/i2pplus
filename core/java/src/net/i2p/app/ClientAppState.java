package net.i2p.app;

/**
 *  Status of a client application.
 *  ClientAppManager.notify() must be called on all state transitions except
 *  from UNINITIALIZED to INITIALIZED.
 *
 *  @since 0.9.4
 */
public enum ClientAppState {
    /** initial value */
    UNINITIALIZED,
    /** after constructor is complete */
    INITIALIZED,
    /** starting up */
    STARTING,
    /** startup failed */
    START_FAILED,
    /** running */
    RUNNING,
    /** stopping */
    STOPPING,
    /** stopped normally */
    STOPPED,
    /** stopped abnormally */
    CRASHED,
    /** forked as a new process, status unknown from now on */
    FORKED
}
