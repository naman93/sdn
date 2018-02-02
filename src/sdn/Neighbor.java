package sdn;

import java.io.*;
import java.net.*;

//this class holds information about a Neighbor to a given switch
public class Neighbor implements Serializable {
	//serialVersionUID
	private static final long serialVersionUID = 3L;
	//ID of the neighboring switch
	Integer neighborId;
	//IP address
	InetAddress ipAddress;
	//port number
	Integer port;
	//link bandwidth
	Integer linkBw;
	//link delay
	Integer linkDelay;
	//link status;
	Boolean linkStatus;
	//link status timestamp
	long linkStatusTimestamp;
	
	//constructor
	public Neighbor() {
		neighborId = -1;
		linkBw = -1;
		linkDelay = -1;
		linkStatus = false;
		linkStatusTimestamp = -1;
	}
}