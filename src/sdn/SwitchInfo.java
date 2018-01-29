package sdn;

import java.io.*;
import java.util.*;
import java.net.*;

//this class holds all the information regarding a switche
public class SwitchInfo implements Serializable {
	//serialVersionUID
	private static final long serialVersionUID = 2L;
	//ID assigned to switch (as per topology map)
	Integer id;
	//IP address
	InetAddress ipAddress;
	//port number
	Integer port;
	//alive status
	Boolean alive;
	//information about neighbors
	HashMap<Integer, Neighbor> neighbors;
	//information about next hop for routing
	HashMap<Integer, Integer> nextHop;
	
	//constructor
	public SwitchInfo() {
		id = -1;
		ipAddress = null;
		port = -1;
		alive = false;
		neighbors = new HashMap<Integer, Neighbor>();
		nextHop = new HashMap<Integer, Integer>();
	}
}