package sdn;

import java.io.*;
import java.util.*;
import java.net.*;
public class Switch {
 private DatagramSocket socket;
 private SwitchInfo info;
 private InetAddress ControllerAddress;
 private Integer ControllerPort;
 private static final Integer wdtSleepMillis = 3000;
 //number of cycles to wait before declaring a switch dead
 private static final Integer waitCycles = 4;
 //duration beyond with a switch is declared dead
 private static final float maxDuration = wdtSleepMillis * waitCycles;
 
public Switch(Integer id, String ControllerAddr,Integer Cntrlport) throws UnknownHostException {
 ControllerPort = Cntrlport;
 ControllerAddress = InetAddress.getByName(ControllerAddr);
 info = new SwitchInfo();
 info.id = id;
 info.alive = true;
try {
    socket = new DatagramSocket();
}
catch (SocketException e) {
    e.printStackTrace();
    System.exit(1);
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
     
     
     synchronized (info) {
      
      
      //check the timestamp recorded in each switch against the current time if the switch is alrady alive
      for (Integer id : info.neighbors.keySet()) {
       if (info.neighbors.get(id).linkStatus.equals(true) && ((currentTime - info.neighbors.get(id).linkStatusTimestamp) > maxDuration)) {
        //mark switch as dead
        info.neighbors.get(id).linkStatus = false;
        //log("watchdog thread: following switch is dead: "+id.toString(), Verbosity.MEDIUM);
        
       }
       else {
    	   //check if we the neighboring switch info
    	   	if (info.neighbors.get(id).ipAddress != null && info.neighbors.get(id).linkStatus.equals(true)) {
    	   		System.out.println("Sending keep alive");
    	   		Message msg = new Message(info.id,"KEEP_ALIVE",info);
    	   		sendMessage(msg,id);
    	   	}
        }
      }
     }
     
     synchronized(info) {
      Message msg = new Message(info.id, "TOPOLOGY_UPDATE", info);
      sendMessage(msg,0);
     }
     
     
    }
   }
   catch (InterruptedException e) {
    e.printStackTrace();
    System.exit(1);
   }
  }
 }
public static void main(String[] argv) throws IOException {
 Switch switc = new Switch(Integer.valueOf(argv[0]), argv[1], Integer.valueOf(argv[2]));
 switc.bootstrap();
 Switch.WatchdogThread wdt = switc.new WatchdogThread();
 Thread t = new Thread(wdt);
 t.start();
 while(true) {
  Message msg = switc.receiveMessage();
  switc.handleMessage(msg);  
 }
  
}
 private void bootstrap() {
  Message msg = new Message(info.id,"REGISTER_REQUEST", info);
  sendMessage(msg,0);
  Message mssg = receiveMessage();
  info = mssg.swInfo;
 }
 private void sendMessage(Message msg, Integer destid) {
  try {
         //create a new byte array output stream
         ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
         //create an object output stream
         ObjectOutputStream oos = new ObjectOutputStream(byteStream);
         oos.writeObject(msg);
         DatagramPacket packet;
         if(destid.equals(0))
         {
          packet = new DatagramPacket(byteStream.toByteArray(), byteStream.size(),ControllerAddress,ControllerPort);}
         else {
           packet = new DatagramPacket(byteStream.toByteArray(), byteStream.size(),info.neighbors.get(destid).ipAddress,info.neighbors.get(destid).port);
         }
         
         socket.send(packet);
  }
   catch (IOException e) {
             e.printStackTrace();
             System.exit(1);
         }}
 private Message receiveMessage() {
   Message msg = new Message(null, null, null);
         //buffer to receive the incoming Message
         byte[] buf = new byte[2048];
 
  try {
             DatagramPacket packet = new DatagramPacket(buf, buf.length);
             socket.receive(packet);
             //create a bytearray input stream
             ByteArrayInputStream inStr = new ByteArrayInputStream(buf);
             //create object input stream
             ObjectInputStream ois = new ObjectInputStream(inStr);
             msg = (Message)ois.readObject();
             if(msg.header.equals("KEEP_ALIVE")) {
              synchronized (info) {
               info.neighbors.get(msg.switchId).ipAddress = packet.getAddress();
               info.neighbors.get(msg.switchId).port = packet.getPort();
              }
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

  return msg;
 }
 
 private void handleMessage(Message msg) {
  if (msg.header.equals("KEEP_ALIVE")) {
   synchronized(info) {
	System.out.println("received keep alive from : " + msg.switchId);
    info.neighbors.get(msg.switchId).linkStatus = true;
    info.neighbors.get(msg.switchId).linkStatusTimestamp = System.currentTimeMillis();
   }
  }
  else if (msg.header.equals("ROUTE_UPDATE")) {
   synchronized(info) {
	System.out.println("received route update : ");
    info.nextHop = msg.swInfo.nextHop;
   }
  }
 }
}
  
  
 

