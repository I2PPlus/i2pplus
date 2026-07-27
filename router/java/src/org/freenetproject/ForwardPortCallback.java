package org.freenetproject;

import java.util.Map;

/**
 * Callback called by port forwarding plugins to indicate success or failure.
 * @author toad
 */
public interface ForwardPortCallback {

	/**
	 * Called to indicate status on one or more forwarded ports.
	 *
	 * @param statuses the port status map
	 */
	public void portForwardStatus(Map<ForwardPort,ForwardPortStatus> statuses);

}
