package org.tamlyn.billy;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.res.AssetManager;
import android.os.Handler;
import android.util.Log;

public class SimpleWebServer extends Thread {
		
	private ServerSocket serverSocket; //socket that listens for clients
	private int requestCounter;        //increments with every request received
	private Handler handler;           //communicate with UI thread
	private String ip;                 //local IP
	private int port;                  //port to listen on
	private AssetManager assetManager; //used to access app's internal files
	private boolean isRunning = true;  //not used
	
	public SimpleWebServer(AssetManager assetManager, Handler handler) {
		this.ip = getLocalIpAddress();
		this.port = 8080;
		this.assetManager = assetManager;
		this.handler = handler;
	}
	
//	public void terminate() {
//		try {
//			serverSocket.close();
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
//		isRunning = false;
//	}
	
	public void run() {
		try {
			serverSocket = new ServerSocket(port);
			logMessage("Listening on "+ip+":"+port);
			
			//loop forever
			while (isRunning) {
				//block waiting for client connection
				Socket clientSocket = serverSocket.accept();
				//create new thread to handle client connection 
				@SuppressWarnings("unused")
				Thread connection = new ConnectionThread(clientSocket);
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	/*
	 * Send a message to the UI thread
	 */
	private void logMessage(String message) {
		handler.sendMessage(handler.obtainMessage(1, message));
	}

	/*
	 * Try to find the IP address of the device's external interface 
	 */
	private String getLocalIpAddress() {
		try {
			for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
				NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
					InetAddress inetAddress = enumIpAddr.nextElement();
					if (!inetAddress.isLoopbackAddress()) { 
						return inetAddress.getHostAddress().toString(); 
					}
				}
			}
		} catch (SocketException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public class ConnectionThread extends Thread {
		private Socket client;
		private PrintStream outStream;
		private Pattern commandPattern = Pattern.compile("(GET|POST) (/.*?)(\\?(.*))? HTTP/1");		
		
		ConnectionThread(Socket client) {
			this.client = client;
			this.start();
		}
		
		public void run()  {
			try {
				outStream = new PrintStream(client.getOutputStream(), true);

				BufferedReader in = new BufferedReader(
						new InputStreamReader(client.getInputStream()));
				
				String line = in.readLine(); //read first line of request
				
				logMessage(line);
				
				Matcher commandMatcher = commandPattern.matcher(line);
				if (commandMatcher.find()) {
					//String method = commandMatcher.group(1);
					String path = commandMatcher.group(2);
					String[] pathBits = path.split("/");
					
					//parse query string
					Map<String, String> queryMap = new HashMap<String, String>();
					if (commandMatcher.group(4) != null) {
						String[] params = commandMatcher.group(4).split("&");  
						for (String param : params)  
						{  
							String[] splat = param.split("=");
							queryMap.put(splat[0], splat.length>1 ? splat[1] : "");
						}
					}
					
					if (queryMap.get("counter")!=null) {
						int newCount = Integer.parseInt(queryMap.get("counter"));
						if (newCount > requestCounter) {
							requestCounter = newCount;
						} else {
							client.close();
							return; //older request so ignore it
						}
					}
					
					if (pathBits.length > 1 && pathBits[1].equals("control")) {
						//control the motors
						setSpeed(
							Float.parseFloat(queryMap.get("left")),
							Float.parseFloat(queryMap.get("right"))
						);
						
						sendResponse("200 OK", path);
					} else if (pathBits.length > 1 && pathBits[1].equals("sensor")) {
						//get sensor data
//						imageData = null;
//						
//						camera.setOneShotPreviewCallback(new PreviewCallback() {
//							public void onPreviewFrame(byte[] data, Camera camera) {
//								imageData = data;
//							}
//						});
//						
//						//wait for image data
//					    while(imageData == null) {
//					    	sleep(10);
//					    }
					    
					    //Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
					    //Matrix matrix = new Matrix();
					    //matrix.postRotate(90);
					    //Bitmap rotatedBMP = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
					    //ByteArrayOutputStream baos=new ByteArrayOutputStream();
					    //bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
					    
//					    YuvImage yuv = new YuvImage(imageData, ImageFormat.NV21, previewSize.width, previewSize.height, null);
//					    Rect rect = new Rect(0, 0, previewSize.width, previewSize.height);
//					    ByteArrayOutputStream baos=new ByteArrayOutputStream();
//					    yuv.compressToJpeg(rect, 70, baos);
//					    
//					    sendHeaders("200 OK", "image/jpeg", baos.size());
//						outStream.write(baos.toByteArray());
					} else {
						//server a file
						if (path.equals("/") || path.equals("/index.html")) {
							path = "/index.html";
							requestCounter = 0;
						}
						
						//determine content type from file extension
						String ext = path.substring(path.lastIndexOf(".")+1);
						String mimeType = "text/plain";
						if (ext.equals("html")) mimeType = "text/html";
						if (ext.equals("js")) mimeType = "text/javascript";
						if (ext.equals("css")) mimeType = "text/css";
						if (ext.equals("ico")) mimeType = "image/x-icon";
						//if (ext.equals("png")) mimeType = "image/png";
						
						//read file into a string
						try {
							BufferedReader fileReader = new BufferedReader(
									new InputStreamReader(assetManager.open(path.substring(1))));
							String body = "";
							String fileLine;
							while ((fileLine = fileReader.readLine()) != null) body = body.concat(fileLine+"\n");
							sendResponse("200 OK", body, mimeType);						
						} catch (FileNotFoundException e) {
							sendResponse("404 File Not Found", "File not found: "+path);
						}
						
					}
				} else {
					sendResponse("400 Bad Request", "Malformed request: "+line);
				}

				client.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
			
		}
		
		private void setSpeed(float leftSpeed, float rightSpeed) {

			if (Math.abs(leftSpeed) < 0.3) leftSpeed = 0;
			if (Math.abs(rightSpeed) < 0.3) rightSpeed = 0;
			
			if (Math.abs(leftSpeed) > 0.8) leftSpeed = leftSpeed > 0 ? 0.8f : -0.8f;
			if (Math.abs(rightSpeed) > 0.8) rightSpeed = rightSpeed > 0 ? 0.8f : -0.8f;
			
			handler.sendMessage(handler.obtainMessage(2, (int)Math.floor(leftSpeed*1000), (int)Math.floor(rightSpeed*1000)));
		}

		/*
		 * Print HTTP response with various kinds of inpiut
		 */
		private void sendResponse(String code, String body, String type) {
			sendHeaders(code, type, body.length());
			outStream.print(body);
		}
		
		private void sendResponse(String code, String body) {
			sendResponse(code, body, "text/plain");
		}
		
		private void sendHeaders(String code, String type, int contentLength) {
			outStream.println("HTTP/1.1 "+code);
			outStream.println("Content-Type: "+type);
			outStream.println("Content-Length: "+contentLength);
			outStream.println("Access-Control-Allow-Origin: *");
			outStream.println();
		}
		
	}

}