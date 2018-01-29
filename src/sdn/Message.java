package sdn;

import java.io.*;

public class Message implements Serializable {
	//serialVersionUID
	private static final long serialVersionUID = 1L;
	//ID of the switch to or from which this message is being sent
	Integer switchId;
	//message header
	String header;
	//objects to be sent in the message
	//switch info
	SwitchInfo swInfo;
	
	public Message(Integer switchId, String header, SwitchInfo swInfo) {
		this.switchId = switchId;
		this.header = header;
		this.swInfo = swInfo;
	}
}