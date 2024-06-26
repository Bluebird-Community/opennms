/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package org.opennms.netmgt.asterisk.monitor;

import java.lang.reflect.UndeclaredThrowableException;
import java.net.SocketTimeoutException;
import java.util.Map;

import org.asteriskjava.manager.AuthenticationFailedException;
import org.asteriskjava.manager.ManagerConnection;
import org.asteriskjava.manager.ManagerConnectionFactory;
import org.asteriskjava.manager.TimeoutException;
import org.asteriskjava.manager.action.SipShowPeerAction;
import org.asteriskjava.manager.response.ManagerResponse;
import org.opennms.core.utils.ParameterMap;
import org.opennms.core.utils.TimeoutTracker;
import org.opennms.netmgt.config.AmiPeerFactory;
import org.opennms.netmgt.config.ami.AmiAgentConfig;
import org.opennms.netmgt.poller.MonitoredService;
import org.opennms.netmgt.poller.PollStatus;
import org.opennms.netmgt.poller.support.AbstractServiceMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <P>
 * This class is designed to be used by the service poller framework to test the
 * availability of Asterisk SIP Peers by executing a "sip show peers" over AMI. 
 * It gets the AMI parameters from the AMI configuration and needs the parameter 
 * sip-peer to be set in the poller configuration.
 * </P>
 *
 * @author <A HREF="mailto:michael.batz@nethinks.com">Michael Batz</A>
 * @author <A HREF="http://www.opennms.org/">OpenNMS </A>
 */
public class AsteriskSIPPeerMonitor extends AbstractServiceMonitor {
	private static final Logger LOG = LoggerFactory.getLogger(AsteriskSIPPeerMonitor.class);

	/**
	  * Default retries.
	  */
	private static final int DEFAULT_RETRY = 0;

	/**
	  * Default timeout. Specifies how long (in milliseconds) to block waiting for data from the
	  * monitored interface.
	  */
	private static final int DEFAULT_TIMEOUT = 3000; // 3 second timeout on read()

	/**
	  * Default sip peer. Specifies the sip peer to get information from the Asterisk server.
	  */
	private static final String DEFAULT_SIPPEER = ""; 

	/**
	  * {@inheritDoc}
	  *
	  * <P>
	  * Initialize the service monitor.
	  * </P>
	  * @exception RuntimeException
	  *		Thrown if an unrecoverable error occurs that prevents the
	  *		plug-in from functioning.
	  */
	public void initialize(Map<String, Object> parameters) 
	{
		try
		{
			AmiPeerFactory.init();
		}
		catch(Exception e)
		{
			LOG.error("Initalize: Failed to load AMI configuration", e);
			throw new UndeclaredThrowableException(e);
		}
		return;
	}

	/**
	  * {@inheritDoc}
	  *
	  * <P>
	  * Run the service monitor and return the poll status
	  * </P>
	  */
	public PollStatus poll(MonitoredService svc, Map<String, Object> parameters)
	{

		//read configuration parameters
		String sipPeer = ParameterMap.getKeyedString(parameters, "sip-peer", DEFAULT_SIPPEER);
		if(sipPeer.equals(DEFAULT_SIPPEER))
		{
			LOG.error("AsteriskMonitor: No sip-peer parameter in poller configuration");
			throw new RuntimeException("AsteriskMonitor: required parameter 'sip-peer' is not present in supplied properties.");

		}
		TimeoutTracker timeoutTracker = new TimeoutTracker(parameters, DEFAULT_RETRY, DEFAULT_TIMEOUT);
		AmiPeerFactory amiPeerFactory = AmiPeerFactory.getInstance();
		AmiAgentConfig amiConfig = amiPeerFactory.getAgentConfig(svc.getAddress());

		//setting up AMI connection	
		LOG.debug("{}: Creating new AMI-Connection: {}:{}, {}/{}", svc.getSvcName(), svc.getIpAddr(), amiConfig.getPort(), amiConfig.getUsername(), amiConfig.getPassword());
		ManagerConnectionFactory factory = new ManagerConnectionFactory(svc.getIpAddr(), amiConfig.getPort().orElse(null), amiConfig.getUsername().orElse(null), amiConfig.getPassword().orElse(null));
		ManagerConnection managerConnection;
		if(amiConfig.getUseTls().orElse(false))
		{
			managerConnection = factory.createSecureManagerConnection();
		}
		else
		{
			managerConnection = factory.createManagerConnection();
		}
		managerConnection.setSocketTimeout(new Long(timeoutTracker.getTimeoutInMillis()).intValue());

		//start with polling
		while(timeoutTracker.shouldRetry())
		{
			timeoutTracker.nextAttempt();
			LOG.debug("{}: Attempt {}", svc.getSvcName(), timeoutTracker.getAttempt());
			try
			{
				LOG.debug("{}: AMI login", svc.getSvcName());
				managerConnection.login();

				LOG.debug("{}: AMI sendAction SipShowPeer", svc.getSvcName());
				ManagerResponse response = managerConnection.sendAction(new SipShowPeerAction(sipPeer));
				if(response.getAttribute("Status") == null)
				{
					LOG.debug("{}: service status down", svc.getSvcName());
					return PollStatus.decode("Down", "State of SIP Peer is unknown, because it was not found on the Asterisk server");

				}
				LOG.debug("{}: Response: {}", svc.getSvcName(), response.getAttribute("Status"));

				LOG.debug("{}: AMI logoff", svc.getSvcName());
				managerConnection.logoff();

				if (response.getAttribute("Status").startsWith("OK"))
				{
					LOG.debug("{}: service status up", svc.getSvcName());
					return PollStatus.decode("Up", "OK");
				}
				else
				{
					LOG.debug("{}: service status down", svc.getSvcName());
					return PollStatus.decode("Down", "State of SIP Peer is " + response.getAttribute("Status") + " and not OK");
				}
			}
			catch(AuthenticationFailedException e)
			{
				LOG.debug("{}: AMI AuthenticationError.", svc.getSvcName(), e);
				return PollStatus.decode("Down", "Could not get the state of SIP Peer: AMI AuthenticationError");
			}
			catch(TimeoutException e)
			{
				LOG.debug("{}: TimeOut reached.", svc.getSvcName(), e);
			}
			catch(SocketTimeoutException e)
			{
				LOG.debug("{}: TimeOut reached.", svc.getSvcName(), e);
			}
			catch(Exception e)
			{	
				LOG.error("{}: An Unknown Exception Occurred.", svc.getSvcName(), e);
				return PollStatus.decode("Down", "Could not get the state of SIP Peer: " + e.toString());
			}
		}
		//If none of the retries worked
		return PollStatus.decode("Down", "Could not get the state of SIP Peer: Timeout exceeded");
	}
}


