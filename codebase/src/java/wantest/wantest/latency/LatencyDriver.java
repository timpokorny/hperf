/*
 *   Copyright 2014 Calytrix Technologies
 *
 *   This file is part of wantest.
 *
 *   NOTICE:  All information contained herein is, and remains
 *            the property of Calytrix Technologies Pty Ltd.
 *            The intellectual and technical concepts contained
 *            herein are proprietary to Calytrix Technologies Pty Ltd.
 *            Dissemination of this information or reproduction of
 *            this material is strictly forbidden unless prior written
 *            permission is obtained from Calytrix Technologies Pty Ltd.
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 */
package wantest.latency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.log4j.Logger;

import hla.rti1516e.ParameterHandleValueMap;
import hla.rti1516e.RTIambassador;
import hla.rti1516e.exceptions.RTIexception;
import hla.rti1516e.time.HLAfloat64TimeFactory;
import static wantest.Handles.*;
import wantest.FederateAmbassador;
import wantest.Storage;
import wantest.TestFederate;
import wantest.config.Configuration;
import wantest.events.LatencyEvent;
import wantest.federate.Utils;

public class LatencyDriver
{
	//----------------------------------------------------------
	//                    STATIC VARIABLES
	//----------------------------------------------------------

	//----------------------------------------------------------
	//                   INSTANCE VARIABLES
	//----------------------------------------------------------
	private Logger logger;
	private Configuration configuration;
	private RTIambassador rtiamb;
	private FederateAmbassador fedamb;
	private Storage storage;
	
	// execution parameters
	private double looptime; // time to tick while waiting
	private byte[] payload;
	private List<String> orderedPeers;
	private HLAfloat64TimeFactory timeFactory;

	//----------------------------------------------------------
	//                      CONSTRUCTORS
	//----------------------------------------------------------

	//----------------------------------------------------------
	//                    INSTANCE METHODS
	//----------------------------------------------------------

	public void execute( Configuration configuration,
	                     RTIambassador rtiamb,
	                     FederateAmbassador fedamb,
	                     Storage storage )
	    throws RTIexception
	{
		logger = Logger.getLogger( "wantest" );
		this.configuration = configuration;
		this.rtiamb = rtiamb;
		this.fedamb = fedamb;
		this.storage = storage;
		
		// execution parameters
		this.looptime = ((double)configuration.getLoopWait()) / 1000;
		this.payload = Utils.generatePayload( configuration.getPacketSize() );
		this.orderedPeers = createPeerList();

		logger.info( " ================================" );
		logger.info( " =     Running Latency Test     =" );
		logger.info( " ================================" );
		String sizeString = Utils.getSizeString( configuration.getPacketSize() );
		logger.info( "Minimum message size="+sizeString );
		logger.info( "Federate order: "+this.orderedPeers );
		
		// enable our time policy
		this.enableTimePolicy();
		
		// Confirm that everyone is ready to proceed
		this.waitForStart();
		
		// Loop
		for( int i = 0; i < configuration.getLoopCount(); i++ )
		{
			loop( i+1 );
		}

		// Confirm that everyone is ready to complete
		this.waitForFinish();

		logger.info( "Latency Test Finished" );
		logger.info( "" );
	}

	/**
	 * Returns a list of all participating federates, sorted in a consistent manner across
	 * all federates.
	 */
	private List<String> createPeerList()
	{
		List<String> list = new ArrayList<String>();
		list.add( configuration.getFederateName() );
		for( TestFederate testFederate : storage.getPeers() )
			list.add( testFederate.getFederateName() );
		
		Collections.sort( list );
		return list;
	}

	////////////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////// Time Policy Methods ///////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////
	private void enableTimePolicy() throws RTIexception
	{
		this.timeFactory = (HLAfloat64TimeFactory)rtiamb.getTimeFactory();

		// turn on the time policy
		rtiamb.enableTimeConstrained();
		rtiamb.enableTimeRegulation( timeFactory.makeInterval(1.0) );
		while( fedamb.timeConstrained == false || fedamb.timeRegulating == false )
			rtiamb.evokeMultipleCallbacks( 0.5, 1.0 );

	}
	
	////////////////////////////////////////////////////////////////////////////////////////////
	/////////////////////////////////////////// Loop ///////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////
	/**
	 * This method performs the main portion of the test. For each loop, it will send out
	 * attribute value updates for each of the objects that it is managing
	 */
	private void loop( int loopNumber ) throws RTIexception
	{
		logger.info( "Processing loop ["+loopNumber+"]" );

		// cache a couple of things that we use multiple times
		int peerCount = orderedPeers.size();

		// if we do our test initially, then `currentTime % peerCount` will be 0.
		// As we use the mod for an index into the ordered sender list, we need
		// it to be this way, but it's also our sign that this loop is over. This
		// is why we use a do..while() - by the time it gets to the test, time
		// has moved forward one step, getting us past the problem
		do
		{
			// whose turn is it to send the ping?
			int senderIndex = (int)fedamb.currentTime % peerCount;
			String senderName = orderedPeers.get( senderIndex );

			// Each interaction is sent with a serial to identify it - we calculate this
			// so that it continually increases. 
			int serial = (loopNumber-1) * peerCount + senderIndex + 1;

			// do the sending (or listening)
			if( configuration.getFederateName().equals(senderName) )
			{
				sendInteractionAndWait( serial );
			}
			else
			{
				respondToInteractions();
			}
		}
		while( fedamb.currentTime % peerCount != 0 );
	}

	/**
	 * It's our turn to initiate the test. Send an interaction and then wait for all the
	 * responses to filter back in before advancing time. 
	 */
	private void sendInteractionAndWait( int serial ) throws RTIexception
	{
		ParameterHandleValueMap parameters = rtiamb.getParameterHandleValueMapFactory().create( 3 );
		parameters.put( PC_LATENCY_SERIAL, Utils.intToBytes(serial) );
		parameters.put( PC_LATENCY_SENDER, configuration.getFederateName().getBytes() );
		parameters.put( PC_LATENCY_PAYLOAD, payload );
		
		// setup the event and store in fedamb so we can record pings
		long timestamp = System.currentTimeMillis();
		int responseCount = configuration.getPeers().size();
		LatencyEvent event = new LatencyEvent( serial, timestamp, responseCount, payload.length );
		
		// send the interaction!
		rtiamb.sendInteraction( IC_LATENCY, parameters, null );

		// store the event information
		storage.addLatencyEvent( event );
		fedamb.sendingSerial = serial;
		fedamb.currentLatencyEvent = event;

		// wait until we have all the responses before we request a time advance
		while( event.hasReceivedAllResponses() == false )
			rtiamb.evokeMultipleCallbacks( looptime, looptime*4.0 );

		// step forward, releasing everyone else
		long requestedTime = fedamb.currentTime+1;
		rtiamb.timeAdvanceRequest( timeFactory.makeTime(requestedTime) );
		while( fedamb.currentTime < requestedTime )
			rtiamb.evokeMultipleCallbacks( looptime, looptime*4.0 );
	}

	/**
	 * It's someone elses turn to initiate the test. Monitor the fedamb for incoming
	 * requests and response to them appropriately. We try to advance time right away,
	 * noting that we'll be held until the initiating federate does so as well, and it
	 * won't until its got all its responses.
	 */
	private void respondToInteractions() throws RTIexception
	{
		// request a time advance immediately - we won't get it until the federate
		// whose turn it is to initiate has got all our responses
		long requestedTime = fedamb.currentTime+1;
		rtiamb.timeAdvanceRequest( timeFactory.makeTime(requestedTime) );

		// wait until we get the grant
		while( fedamb.currentTime < requestedTime )
		{
			// tick for a bit
			rtiamb.evokeCallback( looptime );
			
			// check to see if we need to send our response yet
			if( fedamb.requestedSerial != -1 )
			{
				ParameterHandleValueMap parameters = rtiamb.getParameterHandleValueMapFactory().create( 3 );
				parameters.put( PC_LATENCY_SERIAL, Utils.intToBytes(fedamb.requestedSerial) );
				parameters.put( PC_LATENCY_SENDER, configuration.getFederateName().getBytes() );
				parameters.put( PC_LATENCY_PAYLOAD, payload );
				rtiamb.sendInteraction( IC_LATENCY, parameters, null );
				
				// we've responded, reset the serial
				fedamb.requestedSerial = -1;
			}
		}			
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////// Lifecycle Methods /////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////
	private void waitForStart() throws RTIexception
	{
		// achieve the ready-to-start sync point
		rtiamb.synchronizationPointAchieved( "START_LATENCY_TEST" );
		
		// wait for everyone to do the same
		while( fedamb.startLatencyTest == false )
			rtiamb.evokeMultipleCallbacks( 0.1, 1.0 );
	}

	private void waitForFinish() throws RTIexception
	{
		rtiamb.synchronizationPointAchieved( "FINISH_LATENCY_TEST" );

		// wait for everyone to do the same
		while( fedamb.finishedLatencyTest == false )
			rtiamb.evokeMultipleCallbacks( 0.1, 1.0 );
	}
	
	//----------------------------------------------------------
	//                     STATIC METHODS
	//----------------------------------------------------------
}
