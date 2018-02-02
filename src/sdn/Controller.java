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
            				switches.put(i, new SwitchInfo());
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
					Thread.sleep(2000);
					log("hello world !", Verbosity.HIGH);
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
		controller.log("main: Controller object created", Verbosity.HIGH);
		//bootstrap
		controller.bootstrap();
		//start the watchdog thread
		Controller.WatchdogThread wdt = controller.new WatchdogThread(); 
		Thread t = new Thread(wdt);
		t.start();
		//infinite loop
		controller.log("main: starting infinite loop", Verbosity.MEDIUM);
		while(true) {
			//receive incoming messages
			Message msg = controller.receiveMessage();
			controller.log("main: received " + msg.header + " message from switch: " + msg.switchId, Verbosity.HIGH);
			//handle the received message
			controller.handleMessage(msg);
		}
	}
	
	//function that ensures that controller performs bootstrap (waits for all the switches in the topology to register)
	private void bootstrap()  {
		log("bootstrap: Bootstrap Started", Verbosity.MEDIUM);
		//create a hash set with all the switch ids that have not registered yet.
		HashSet<Integer> unregisteredSwitches = new HashSet<Integer>();
		//add all the keys
		for (Integer id : switches.keySet()) {
			unregisteredSwitches.add(id);
		}
		log("bootstrap: Waiting for all switches to register", Verbosity.MEDIUM);
		//wait until all the switches register
		while(!unregisteredSwitches.isEmpty()) {
			//read a message from socket
			Message msg = receiveMessage();
			//check if a register request message was received
			if (msg.header.equals("REGISTER_REQUEST")) {
				log("bootstrap: received register request from switch: "+msg.switchId.toString(), Verbosity.MEDIUM);
				//set the alive status of the switch which sent register request to true
				(switches.get(msg.switchId)).alive = true;
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
			log("routeUpdate: sending route update to switch: "+id.toString(), Verbosity.HIGH);
			Message msg = new Message(id, "ROUTE_UPDATE", switches.get(id));
			sendMessage(msg);
		}
		log("bootstrap: sent route update to all switches", Verbosity.MEDIUM);
		log("bootstrap: Bootstrap done", Verbosity.LOW);
	}
	
	private void computeRoute() {
		//TODO: implement widest path algorithm
	}
	
	//function to handle incoming messages
	private void handleMessage(Message msg) {
		//TODO: implement handleMessage function
	}
	
	//function to read  a message from socket
	private Message receiveMessage() {
		//build the new message object
		Message msg = new Message(null, null, null);
		//buffer to receive the incoming message
		byte[] buf = new byte[2048];
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
						if (neighbors.containsKey(id)) {
							Neighbor nb = neighbors.get(id);
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
	        //synchronized block
	        synchronized (socket) {
	        		socket.send(packet);
	        }
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