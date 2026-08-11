package net.i2p.app;

/**
 *  Status of a client application.
 *  ClientAppManager.notify() must be called on all state transitions except
 *  from UNINITIALIZED to INITIALIZED.
 *
 *  @since 0.9.4
 */
public enum ClientAppState {
    /** Initial value. */
    UNINITIALIZED,
    /** After constructor is complete. */
    INITIALIZED,
    /** Starting up. */
    STARTING,
    /** Startup failed. */
    START_FAILED,
    /** Running. */
    RUNNING,
    /** Stopping. */
    STOPPING,
    /** Stopped normally. */
    STOPPED,
    /** Stopped abnormally. */
    CRASHED,
    /** Forked as a new process, status unknown from now on. */
    FORKED
}
