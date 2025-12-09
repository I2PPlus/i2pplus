package org.freenetproject;

/**
 * Represents the status of a port forwarding operation.<br>
 * This class encapsulates the result of UPnP port forwarding attempts,
 * including success/failure status, reason messages, and external port information.
 *
 * @author Freenet Project
 * @version 1.0
 */
public class ForwardPortStatus {

	/** The current status of the port forwarding operation */
	public final int status;
	/** The port forward definitely succeeded. */
	public static final int DEFINITE_SUCCESS = 3;
	/** The port forward probably succeeded. I.e. it succeeded unless there was
	 * for example hostile action on the part of the router. */
	public static final int PROBABLE_SUCCESS = 2;
	/** The port forward may have succeeded. Or it may not have. We should
	 * definitely try to check out of band. See UP&amp;P: Many routers say they've
	 * forwarded the port when they haven't. */
	public static final int MAYBE_SUCCESS = 1;
	/** The port forward is in progress */
	public static final int IN_PROGRESS = 0;
	/** The port forward probably failed */
	public static final int PROBABLE_FAILURE = -1;
	/** The port forward definitely failed. */
	public static final int DEFINITE_FAILURE = -2;

	/** A descriptive message explaining the status or reason for failure */
	public final String reasonString;

	/** The external port that was actually forwarded. Some plugins may need to
	 * change the external port and can return it to the node here. */
	public final int externalPort;

	/**
	 * Creates a new ForwardPortStatus with the specified status, reason, and external port.
	 *
	 * @param status the status code of the port forwarding operation
	 * @param reason a descriptive message explaining the status or reason for failure
	 * @param externalPort the external port that was actually forwarded
	 */
	public ForwardPortStatus(int status, String reason, int externalPort) {
		this.status = status;
		this.reasonString = reason;
		this.externalPort = externalPort;
	}
}
