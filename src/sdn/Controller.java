package sdn;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.net.*;

//enum for logging verbosity
enum Verbosity {
	LOW, MEDIUM, HIGH
}

public class Controller {
	//sdn topology
	private HashMap<Integer, SwitchInfo> switches;
	//controller socket
	private DatagramSocket socket;	
	//size of byte buffer
	private static final int bufferSize = 2048;
	//watchdog thread sleep duration
	private static final Integer wdtSleepMillis = 3000;
	//number of cycles to wait before declaring a switch dead
	private static final Integer waitCycles = 4;
	//duration beyond with a switch is declared dead
	private static final float maxDuration = wdtSleepMillis * waitCycles;
	//logging verbosity
	private Verbosity logVerbosity;
	
	//constructor
	public Controller(Integer port, String topologyFileName, String verbosity) {
		//create a socket and bind it to a known port number
		try {
			socket = new DatagramSocket(port);
		}
		catch (SocketException e) {
			e.printStackTrace();
			System.exit(1);
		}
		//create a HashMap instance
		switches = new HashMap<Integer, SwitchInfo>();
		//read the topology file and populate "switches" HashMap
	    try {
            //FileReader
            FileReader fileReader = new FileReader(topologyFileName);
            //wrap FileReader in BufferedReader.
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            String line = null;
            boolean numSwitchesRead = false;
            //regular expression patter to match
            String pattern = "([0-9]+) ([0-9]+) ([0-9]+) ([0-9]+)";
            Pattern p = Pattern.compile(pattern);
            while((line = bufferedReader.readLine()) != null) {
                //read the number of switches in the network
            		if (!numSwitchesRead) {
            			//create an entry for each switch in the hashmap
            			Integer numSwitches = Integer.valueOf(line);
            			for (int i=1; i<=numSwitches; i++) {
            				SwitchInfo info = new SwitchInfo();
            				info.id = i;
            				switches.put(i, info);
            			}
            			//number of switches in the topology has been read
            			numSwitchesRead = true;
            		}
            		//read link information
            		else {
            			Matcher m = p.matcher(line);
            			if (m.find()) {
            				Integer id1 = Integer.valueOf(m.group(1));
            				Integer id2 = Integer.valueOf(m.group(2));
            				Integer bw = Integer.valueOf(m.group(3));
            				Integer delay = Integer.valueOf(m.group(4));
            				//add id2 as neighbor to id1 and also populate the link information
            				//construct a new neighbor object
            				Neighbor nb = new Neighbor();
            				nb.neighborId = id2;
            				nb.linkBw = bw;
            				nb.linkDelay = delay;
            				SwitchInfo swInfo = switches.get(id1);
            				swInfo.neighbors.put(id2, nb);
               			//add id1 as neighbor to id2 and also populate the link information
            				//construct a new neighbor object
            				nb = new Neighbor();
            				nb.neighborId = id1;
            				nb.linkBw = bw;
            				nb.linkDelay = delay;
            				swInfo = switches.get(id2);
            				swInfo.neighbors.put(id1, nb);
            			}
            			else {
            				System.out.println("Error parsing file");
            				System.exit(1);
            			}
            		}
            }   
            //close buffered reader
            bufferedReader.close();         
        }
        catch(FileNotFoundException e) {
        		e.printStackTrace();
            System.exit(1);               
        }
        catch(IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
		//set the logging verbosity
		if (verbosity.equals("HIGH")) {
			logVerbosity = Verbosity.HIGH;
		}
		else if (verbosity.equals("MEDIUM")) {
			logVerbosity = Verbosity.MEDIUM;
		}
		else {
			logVerbosity = Verbosity.LOW;
		}
	}
	
	//nested class
	//watchdog thread implementation
	private class WatchdogThread implements Runnable {
		public void run() {
			try {
				while (true) {
					Thread.sleep(wdtSleepMillis);
					//get the current time
					long currentTime = System.currentTimeMillis();
					//boolean to keep track if the status of any switch changed
					Boolean aliveStatusChange = false;
					synchronized (switches) {
						//check the timestamp recorded in each switch against the current time if the switch is alrady alive
						for (Integer id : switches.keySet()) {
							if (switches.get(id).alive.equals(true) && ((currentTime - switches.get(id).aliveTimestamp) > maxDuration)) {
								//mark switch as dead
								switches.get(id).alive = false;
								//mark all links going to dead switch as dead
								for (Integer swId : switches.keySet()) {
									if (switches.get(swId).neighbors.containsKey(id)) {
										switches.get(swId).neighbors.get(id).linkStatus = false;
									}
								}
								log("watchdog thread: following switch is dead: "+id.toString(), Verbosity.MEDIUM);
								aliveStatusChange = true;
							}
							else {}
						}
					}
					//check if the alive status of any of the switches changed
					if (aliveStatusChange) {
						//compute routes
						computeRoute();
						//send route update to all switches
						synchronized (switches) {
							for (Integer id : switches.keySet()) {
								if (switches.get(id).alive) {
									log("watchdog thread: sending route update to switch: "+id.toString(), Verbosity.MEDIUM);
									Message msg = new Message(id, "ROUTE_UPDATE", switches.get(id));
									sendMessage(msg);
								}
								else {}
							}
						}
					}
					else {}
				}
			}
			catch (InterruptedException e) {
				e.printStackTrace();
				System.exit(1);
			}
		}
	}
	
	//main
	public static void main(String[] args) {		
		if (args.length != 3) {
			System.out.println("Usage: java sdn.Controller <ServerPortNumber> <TopologyFileName> <Verbosity:LOW | MEDIUM | HIGH>");
			System.exit(1);
		}
		//create a controller object
		Controller controller = new Controller(Integer.valueOf(args[0]), args[1], args[2]);
		controller.log("main: controller object created", Verbosity.HIGH);
		//bootstrap
		controller.bootstrap();
		//start the watchdog thread
		controller.log("main: starting watchdog thread", Verbosity.MEDIUM);
		Controller.WatchdogThread wdt = controller.new WatchdogThread(); 
		Thread t = new Thread(wdt);
		t.start();
		//infinite loop
		controller.log("main: starting infinite loop in main", Verbosity.MEDIUM);
		while(true) {
			//receive incoming messages
			Message msg = controller.receiveMessage();
			controller.log("main: received " + msg.header + " message from switch: " + msg.switchId, Verbosity.MEDIUM);
			//handle the received message
			controller.handleMessage(msg);
		}
	}
	
	//function that ensures that controller performs bootstrap (waits for all the switches in the topology to register)
	private void bootstrap()  {
		log("bootstrap: bootstrap Started", Verbosity.MEDIUM);
		//create a hash set with all the switch ids that have not registered yet.
		HashSet<Integer> unregisteredSwitches = new HashSet<Integer>();
		//add all the keys
		for (Integer id : switches.keySet()) {
			unregisteredSwitches.add(id);
		}
		log("bootstrap: waiting for all switches to register", Verbosity.MEDIUM);
		//wait until all the switches register
		while(!unregisteredSwitches.isEmpty()) {
			//read a message from socket
			Message msg = receiveMessage();
			//check if a register request message was received
			if (msg.header.equals("REGISTER_REQUEST")) {
				log("bootstrap: received register request from switch: "+msg.switchId.toString(), Verbosity.MEDIUM);
				//set the alive status of the switch which sent register request to true
				(switches.get(msg.switchId)).alive = true;
				//set the link status of all links whose destination is "msg.switchId" to true
				for (Integer swId : switches.keySet()) {
					if (switches.get(swId).neighbors.containsKey(msg.switchId)) {
						switches.get(swId).neighbors.get(msg.switchId).linkStatus = true;
					}
				}
				log("bootstrap: sending register response", Verbosity.MEDIUM);
				//prepare a message to send as response to the switch
				Message responseMessage = new Message(msg.switchId, "REGISTER_RESPONSE", switches.get(msg.switchId));
				//send the response
				sendMessage(responseMessage);
				//delete the id of the switch that registered from unregisteredSwitches
				unregisteredSwitches.remove(msg.switchId);
			}
		}
		//perform route computations
		computeRoute();
		//send the updated routing information to all switches
		for (Integer id : switches.keySet()) {
			log("bootstrap: sending route update to switch: "+id.toString(), Verbosity.MEDIUM);
			Message msg = new Message(id, "ROUTE_UPDATE", switches.get(id));
			sendMessage(msg);
		}
		log("bootstrap: sent route update to all switches", Verbosity.MEDIUM);
		//set the alive timestamp of all switches to current time
		log("bootstrap: setting the alive time stamp of all switches to current time", Verbosity.MEDIUM);
		for (Integer id : switches.keySet()) {
			switches.get(id).aliveTimestamp = System.currentTimeMillis();
		}
		log("bootstrap: bootstrap done", Verbosity.LOW);
	}
	
	private void computeRoute() {
		log("computeRoute: computing widest path routes", Verbosity.MEDIUM);
		synchronized (switches) {
			//iterate over all the switches in the topology
			for (Integer id : switches.keySet()) {
				//check if switch is alive
				if(switches.get(id).alive) {
					//hashmap to store the cost(bandwidth) to next hop switches
					HashMap<Integer, Integer> cost = new HashMap<Integer, Integer>();
					//hashmap to store the parent of each node
					HashMap<Integer, Integer> parent = new HashMap<Integer, Integer>();
					//hashmap to store the next hop information for the given switch
					HashMap<Integer, Integer> nextHop = new HashMap<Integer, Integer>();
					//hashset to store the switches that have already been looked at
					HashSet<Integer> seen = new HashSet<Integer>();
					//populate cost and parent hashmaps with root node
					//store the maximum cost for the root switch (so that it is picked first)
					cost.put(id, Integer.MAX_VALUE);
					//set the parent id to 0 (parent unknown)
					parent.put(id, 0);
					//iterate until the cost hashmap is empty
					while(! cost.isEmpty()) {
						//get the switch with maximum cost out of the hashmap
						Integer maxCostId = 0;
						Integer maxCost = 0;
						for (Integer swId : cost.keySet()) {
							if (cost.get(swId).compareTo(maxCost) > 0) {
								maxCostId = swId.intValue();
								maxCost = cost.get(swId);
							}
						}
						//remove the switch with maximum cost (BW) from the hashmap
						cost.remove(maxCostId);
						//add to set of switches have already been looked at
						seen.add(maxCostId);
						//check the BW of links to neighbors
						for (Integer swId : switches.get(maxCostId).neighbors.keySet()) {
							//check if the link has not been looked at and is alive
							if (!seen.contains(swId) && switches.get(maxCostId).neighbors.get(swId).linkStatus) {
								//check if the neighbor is already present in the cost hashmap
								if (cost.containsKey(swId)) {
									//if the cost is greater then existing cost, then replace it
									if (switches.get(maxCostId).neighbors.get(swId).linkBw.compareTo(cost.get(swId)) > 0) {
										cost.put(swId, switches.get(maxCostId).neighbors.get(swId).linkBw);
										//set parent
										parent.put(swId, maxCostId);
									}
									else {}
								}
								else {
									//add the switch id to the cost hashmap
									cost.put(swId, switches.get(maxCostId).neighbors.get(swId).linkBw);
									//set parent
									parent.put(swId, maxCostId);
								}
							}
							else {}
						}
					}
					//construct the next hop map using the parent map
					for (Integer swId : parent.keySet()) {
						if (parent.get(swId).equals(0)) {
							continue;
						}
						else {
							Integer node = swId;
							Integer prevNode = null;
							while(!parent.get(node).equals(0)) {
								prevNode = node;
								node = parent.get(node);
							}
							//add next hop information
							nextHop.put(swId, prevNode.intValue());
						}
					}
					//store the nextHop hashmap
					switches.get(id).nextHop = nextHop;
				}
				else {}
			}
		}
	}
	
	//function to handle incoming messages
	private void handleMessage(Message msg) {
		//TOPOLOGY_UPDATE
		if (msg.header.equals("TOPOLOGY_UPDATE")) {
			//boolean variable to keep track if any link status changed
			Boolean linkStatusChange = false;
			//synchronized block
			synchronized(switches) {
				SwitchInfo info = switches.get(msg.switchId);
				//mark switch as alive
				info.alive = true;
				//update the timestamp information
				info.aliveTimestamp = System.currentTimeMillis();
				//store the data about neighbors sent by the switch
				//iterate through all the neighbors of the switch and check if there is a change in link status
				for(Integer id : info.neighbors.keySet()) {
					if (info.neighbors.get(id).linkStatus.equals(msg.swInfo.neighbors.get(id).linkStatus)) {}
					else {
						linkStatusChange = true;
						info.neighbors.get(id).linkStatus = msg.swInfo.neighbors.get(id).linkStatus;
						log("handleMessage: link between switch " + msg.switchId + " and switch " + id.toString() + " is down", Verbosity.MEDIUM);
					}
				}
			}
			//check if we need to perform route computation
			if (linkStatusChange) {
				computeRoute();
				synchronized (switches) {
					//send route update to all switches
					for (Integer id : switches.keySet()) {
						log("handleMessage: sending route update to switch: "+id.toString(), Verbosity.MEDIUM);
						Message routeUpdateMsg = new Message(id, "ROUTE_UPDATE", switches.get(id));
						sendMessage(routeUpdateMsg);
					}
				}
			}
		}
		//REGISTER_REQUEST
		else if (msg.header.equals("REGISTER_REQUEST") ) {
			synchronized (switches) {
				//set the switch status to alive
				SwitchInfo info = switches.get(msg.switchId);
				info.alive = true;
				//mark all links going to newly registered switch as alive
				for (Integer swId : switches.keySet()) {
					if (switches.get(swId).neighbors.containsKey(msg.switchId)) {
						switches.get(swId).neighbors.get(msg.switchId).linkStatus = true;
					}
				}
				//set the timestamp value
				info.aliveTimestamp = System.currentTimeMillis();
				//send back a register response
				log("handleMessage: sending REGISTER_RESPONSE to switch " + msg.switchId.toString(), Verbosity.MEDIUM);
				Message responseMessage = new Message(msg.switchId, "REGISTER_RESPONSE", switches.get(msg.switchId));
				//send the response
				sendMessage(responseMessage);
			}
			//perform route computations
			computeRoute();
			//send route update to all switches
			synchronized (switches) {
				//send route update to all switches
				for (Integer id : switches.keySet()) {
					log("handleMessage: sending route update to switch: "+id.toString(), Verbosity.MEDIUM);
					Message routeUpdateMsg = new Message(id, "ROUTE_UPDATE", switches.get(id));
					sendMessage(routeUpdateMsg);
				}
			}
		}
		//ignore unknown message
		else {}
	}
	
	//function to read  a message from socket
	private Message receiveMessage() {
		//build the new message object
		Message msg = new Message(null, null, null);
		//buffer to receive the incoming message
		byte[] buf = new byte[bufferSize];
		try {
			DatagramPacket packet = new DatagramPacket(buf, buf.length);
			log("receiveMessage: waiting to receive a data packet", Verbosity.HIGH);
			socket.receive(packet);
			log("receiveMessage: received a data packet", Verbosity.HIGH);
			//create a bytearray input stream
			ByteArrayInputStream inStr = new ByteArrayInputStream(buf);
			//create object input stream
			ObjectInputStream ois = new ObjectInputStream(inStr);
			msg = (Message)ois.readObject();
			//learn the IP address and port number of the switch that sent the message
			//(if not already populated)
			Integer switchId = msg.switchId;
			if (switches.containsKey(switchId)) {
				//update the ip address and port
				//synchronized block
				synchronized (switches) {
					SwitchInfo swInfo = switches.get(switchId);
					swInfo.ipAddress = packet.getAddress();
					swInfo.port = packet.getPort();
					//update the IP and port address in the neighbor information
					for (Integer id : switches.keySet()) {
						HashMap<Integer, Neighbor> neighbors = (switches.get(id)).neighbors;
						if (neighbors.containsKey(switchId)) {
							Neighbor nb = neighbors.get(switchId);
							nb.ipAddress = packet.getAddress();
							nb.port = packet.getPort();
						}
						else {}
					}
				}
			}
			else {
				//we did not expect to get a message from a switch with this ID
				//(switch not present in the topology map)
				System.out.println("Message received from an unknown switch. Switch ID = " + switchId);
				System.out.println("Exiting !");
				System.exit(1);
			}
	    }
	    catch(ClassNotFoundException e) {
	      e.printStackTrace();
	      System.exit(1);
	    }
		catch(IOException e) {
	      e.printStackTrace();
	      System.exit(1);
		}
		log("receiveMessage: returning message object", Verbosity.HIGH);
	    return msg;
	}
	
	//function to send a message via socket
	private void sendMessage(Message msg) {
		try {
	        //create a new byte array output stream
	        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
	        //create an object output stream
	        ObjectOutputStream oos = new ObjectOutputStream(byteStream);
	        //serialize the object and write to byte stream
	        oos.writeObject(msg);
	        //construct a packet
	        DatagramPacket packet = new DatagramPacket(byteStream.toByteArray(), byteStream.size(), msg.swInfo.ipAddress, msg.swInfo.port);
	        log("sendMessage: sending a data packet", Verbosity.HIGH);
	        	socket.send(packet);
	        log("sendMessage: sent a data packet", Verbosity.HIGH);
		}
		catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}
	
	//function that implements logging functionality
	private synchronized void log(String str, Verbosity ver) {
		if (logVerbosity == Verbosity.HIGH) {
			System.out.println("[" + System.currentTimeMillis() + "]: " + str);
		}
		else if ((logVerbosity == Verbosity.MEDIUM) && (ver != Verbosity.HIGH)) {
			System.out.println("[" + System.currentTimeMillis() + "]: " + str);
		}
		else if ((logVerbosity == Verbosity.LOW) && (ver == Verbosity.LOW)) {
			System.out.println("[" + System.currentTimeMillis() + "]: " + str);
		}
		else {}
	}
}