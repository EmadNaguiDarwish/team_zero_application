package server;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.*;

/**
 * Main Server class and system entry point.
 */
public class ServerMain extends WebSocketServer {


	private static final int LOCAL_PORT = 1234;
	
	/**
	 * Message type information indicators for clients to put in first JSON element.
	 * eg: { type: "REGISTER", ...}
	 */
	private static final String CASE_REGISTER = "REGISTER"; // register new client
	
	private static final String CASE_TEXT_MESSAGE = "TEXT"; // send text to another client 
	
	private static final String CASE_UNREGISTER = "UNREGISTER"; // delete client from system
	
	private static final String CASE_LOGIN = "LOGIN"; //login with user name and password
	
	private static final String CASE_EDIT_PROFILE = "EDIT"; //edit profile details
	
	/**
	 * JSON keys or attributes that the server expects within data sent from the client
	 */
	private static final String JSON_KEY_MESSAGE_TYPE = "type";

	private static final String JSON_KEY_USERNAME = "username";

	private static final String JSON_KEY_PASSWORD = "password";
	
	private static final String JSON_KEY_MESSAGE  = "message";

	private static final String JSON_KEY_SENDER = "sender";
	
	private static final String JSON_KEY_RECIPIENT = "recipient";
	
	private static final String JSON_KEY_EMAIL = "email";
	
	/**
	 * A map of Connections to the client IDs of the clients they connect to
	 */
	private HashMap<WebSocket, Integer> clientWebSockets;
	
	
	/**
	 * A map of recipient client IDs to a list of messages that still need to be sent to them
	 */
	private HashMap<Integer, ArrayList<String>> recipientUnsentMessages;
	
	
	/**
	 * System entry point
	 * @throws UnknownHostException 
	 */
	public static void main(String[] args) throws UnknownHostException {
		System.out.println("System started");
		startServer();
	}
	
	/**
	 * Constructs a ServerMain object, and initializes the map of web socket connections to client IDs
	 * @param port
	 * @throws UnknownHostException
	 */
	public ServerMain(int port) throws UnknownHostException {
		super(new InetSocketAddress(port));
		clientWebSockets = new HashMap<WebSocket, Integer>();
		recipientUnsentMessages = new HashMap<Integer, ArrayList<String>>();
	}
	
	private static void startServer() throws UnknownHostException {
		int port;
		
		try {
			// get port information on heroku
			port = Integer.parseInt(System.getenv("PORT"));
			System.out.println("port is" + port);
		}catch(NumberFormatException e) {
			port = LOCAL_PORT;
		}
		new ServerMain(port).start();
	}

	@Override
	public void onClose(WebSocket websocket, int arg1, String arg2, boolean arg3) {
		
		// remove client from connected clients manager and from client websockets array
		ClientManager.getInstance().removeClient(clientWebSockets.get(websocket));
		clientWebSockets.remove(websocket);
		System.out.println("Client connection closed: " + websocket.toString()); 
		
	}

	@Override
	public void onError(WebSocket arg0, Exception arg1) {
		System.out.println(arg1.toString());
		
	}

	/**
	 * Called when a message is received from a remote host. Expected message is in JSON format.
	 */
	@Override
	public void onMessage(WebSocket websocket, String message) {
		System.out.println(message); 
		websocket.send(message);

		//parse JSON and get first element to check type
		try {
			JSONObject json = new JSONObject(message);
			String msgType = json.getString(JSON_KEY_MESSAGE_TYPE);
			if (msgType == CASE_LOGIN) {
				String userName = json.getString(JSON_KEY_USERNAME);
				String password = json.getString(JSON_KEY_PASSWORD);

				DbConnection dbConnection = new DbConnection();
				Client authenticatedClient = dbConnection.authenticateUser(userName, password);
				// if the client is authenticated, get their info and add to connected clients
				if (authenticatedClient != null) {
					ClientManager.getInstance().addClient(authenticatedClient);
					int id = authenticatedClient.getId();
					clientWebSockets.put(websocket, id);
					
					// check if there are any unsent messages to send & send them
					
					sendUnsentMessages(websocket, id);
					
				}
				else {
					//TODO cant authenticate, send error response
				}
								
			}
			else if (msgType.equals(CASE_REGISTER)) {
				// TODO save pictures
				String userName = json.getString(JSON_KEY_USERNAME);
				String password = json.getString(JSON_KEY_PASSWORD);
				String email = json.getString(JSON_KEY_EMAIL);
				DbConnection dbConnection = new DbConnection();
				dbConnection.addUser(userName, password, email);
			}
			else if (msgType.equals(CASE_TEXT_MESSAGE)) {
				sendClientText(websocket, json);
				
			}
			else if (msgType.equals(CASE_UNREGISTER)) {
				String userName = json.getString(JSON_KEY_USERNAME);
				String password = json.getString(JSON_KEY_PASSWORD);
				DbConnection dbConnection = new DbConnection();
				dbConnection.deleteUser(userName, password);
			}
			else if (msgType.equals(CASE_EDIT_PROFILE)) {
				// TODO edit profile
			}
			else {
				// TODO send error - unknown 
				websocket.send(message);
			}
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		// TODO: check if message from host is a registration request
		// or a chat message to forward and send it 
		// to the appropriate class or methods
		
		

		// TODO add client data to hashmap
		// check if client exists in client manager, if not add.
		// if client exists in client manager, set isOnline tag	
		
	}

	/**
	 * Called when a new connection is established with a remote host.
	 */
	@Override
	public void onOpen(WebSocket websocket, ClientHandshake handshake) {
		
		System.out.println("Connection established with a client.");
		System.out.println("WebSocket: " + websocket.toString());
		System.out.println("Handshake: " + handshake.toString());
		
	}
	
	
	/**
	 * Send a text message from the sender (websocket) to the recipient stated within the JSON message
	 * @param websocket
	 * @param message
	 * @throws JSONException
	 */
	private void sendClientText(WebSocket websocket, JSONObject message) throws JSONException {
		/*
		 * expecting the following json

			{
				type: TEXT
				sender: fromUsername
				recipient: toUsername
				message: message
			}
		*/
		
		String recipientUsername = message.getString(JSON_KEY_RECIPIENT);
		Client recipient = ClientManager.getInstance().getClientByUsername(recipientUsername);
		WebSocket recipientSocket = getWebSocketByClientId(recipient.getId());
		
		if (recipientSocket != null) {
			// if recipient is connected, send message right away
				recipientSocket.send(message.toString());
		}
		else {
			// if recipient is not currently connected
			// insert message into unsent messages map
			
			int rId = recipient.getId();
			ArrayList<String> unsentMessages;

			// check if there already are any unsent messages
			if (recipientUnsentMessages.containsKey(rId)){
				unsentMessages = recipientUnsentMessages.get(rId);
				unsentMessages.add(message.toString());
				
				// but the updated array list back in the map
				recipientUnsentMessages.put(rId, unsentMessages);
			}
			else { 
				// if there are no previous messages, add a new array with one message
				unsentMessages = new ArrayList<String>();
				unsentMessages.add(message.toString());
				recipientUnsentMessages.put(rId, unsentMessages);	
			}
			
		}		
	}
	
	/**
	 * Search for the web socket associated with a client ID within the connected clients hash map
	 * @param id
	 * @return
	 */
	private WebSocket getWebSocketByClientId(int id) {
		
		Iterator<Entry<WebSocket, Integer>> clientIterator = clientWebSockets.entrySet().iterator();
		Entry<WebSocket, Integer> nextSet = null;
		int nextId = 0;
		while(clientIterator.hasNext()) {
			nextSet = clientIterator.next();
			nextId = nextSet.getValue();
			if (nextId == id) {
				// return the web socket that was the key for the client ID value in the hash map
				return nextSet.getKey(); 
			}
		}
		return null;
	}
	
	
	/**
	 * Send messages that were previously unsent to a given web socket. Intended to be called
	 * when a client has newly logged in or reconnected.
	 * @param websocket 
	 */
	private void sendUnsentMessages(WebSocket websocket, int recipientId) {
		ArrayList<String> messagesToSend = recipientUnsentMessages.get(recipientId);
		Iterator<String> iterator = messagesToSend.iterator();
		
		//TODO: currently sends as separate messages. it may be preferable to send a single larger
		// message with all the data?
		while(iterator.hasNext()) {
			String nextMessage = iterator.next();
			websocket.send(nextMessage);
		}
	}

	
	private void editClientProfile (WebSocket websocket, JSONObject message) {
		// TODO edit client profile (picture?)
	}
}
