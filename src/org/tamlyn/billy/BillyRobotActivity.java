package org.tamlyn.billy;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.TextView;

import ioio.lib.api.*;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.AbstractIOIOActivity;

public class BillyRobotActivity extends AbstractIOIOActivity {
	
	private TextView status;
	private Handler handler = new Handler() {
		public void handleMessage(Message msg) {
			status.append((String)msg.obj + "\n");
		}
	};
	private ServerSocket serverSocket;
	final int port = 8080;
	public static String ip;
	public float leftSpeed = 0;
	public float rightSpeed = 0;
	private Thread serverThread;
	private int requestCounter;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		ip = getLocalIpAddress();
		status = (TextView) findViewById(R.id.status);
		status.setText("");
		
	}
	
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
		} catch (SocketException ex) {
			Log.e("BillyRobotActivity", ex.toString());
		}
		return null;
	}
	
	@Override
	protected void onStop() {
		super.onStop();
		try {
			serverSocket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	
	@Override
	protected void onResume() {
		super.onResume();
		serverThread = new Thread(new WebServer());
		serverThread.start();
	}

	@Override
	protected void onPause() {
		super.onPause();
		serverThread.suspend();
		try {
			serverThread.join();
		} catch (InterruptedException e) {
		}
	}

	public class WebServer implements Runnable {
		
		public void run() {
			try {
				if (ip != null) {
					serverSocket = new ServerSocket(port);
					
					Pattern commandPattern = Pattern.compile("(GET|POST) (/.*?)(\\?(.*))? HTTP/1");
					
					while (true) {
						handler.sendMessage(handler.obtainMessage(0, "Listening on "+ip+":"+port));
						
						Socket client = serverSocket.accept(); //blocks waiting for connection
						PrintWriter out = new PrintWriter(client.getOutputStream(), true);
						
						try {
							BufferedReader in = new BufferedReader(
									new InputStreamReader(client.getInputStream()));
							
							String line = in.readLine(); //read first line of request
							handler.sendMessage(handler.obtainMessage(0, line));
							
							Matcher commandMatcher = commandPattern.matcher(line);
							if (commandMatcher.find()) {
								//String method = commandMatcher.group(1);
								String path = commandMatcher.group(2);
								String[] pathBits = path.split("/");
								
								//parse query string
								Map<String, String> map = new HashMap<String, String>();
								if (commandMatcher.group(4) != null) {
									String[] params = commandMatcher.group(4).split("&");  
									for (String param : params)  
									{  
										String[] splat = param.split("=");
										map.put(splat[0], splat.length>1 ? splat[1] : "");
									}
								}
								
								if (map.get("counter")!=null) {
									int newCount = Integer.parseInt(map.get("counter"));
									if (newCount > requestCounter) {
										requestCounter = newCount;
									} else {
										client.close();
										continue; //older request so ignore it
									}
									
								}
								
								if (pathBits.length == 3 && pathBits[1].equals("control")) {
									
									if (pathBits[2].equals("forward")) {
										leftSpeed = 0.5f;
										rightSpeed = 0.5f;
									}
									if (pathBits[2].equals("back")) {
										leftSpeed = -0.5f;
										rightSpeed = -0.5f;
									}
									if (pathBits[2].equals("left")) {
										leftSpeed = -0.3f;
										rightSpeed = 0.3f;
									}
									if (pathBits[2].equals("right")) {
										leftSpeed = 0.3f;
										rightSpeed = -0.3f;
									}
									if (pathBits[2].equals("stop")) {
										leftSpeed = 0.0f;
										rightSpeed = 0.0f;
									}
									sendResponse(out, "200 OK", path);
								} else if (pathBits.length == 2 && pathBits[1].equals("sensor")) {
									sendResponse(out, "200 OK", "Camera not available");
								} else {
									if (path.equals("/") || path.equals("/index.html")) {
										path = "/index.html";
										requestCounter = 0;
									}
									//read file into a string
									AssetManager assetManager = getResources().getAssets();
									try {
										BufferedReader indexReader = new BufferedReader(
												new InputStreamReader(assetManager.open(path.substring(1))));
										String body = "";
										String fileLine;
										while ((fileLine = indexReader.readLine()) != null) body = body.concat(fileLine+"\n");
										sendResponse(out, "200 OK", body, "text/html");
									} catch (FileNotFoundException e) {
										sendResponse(out, "404 File Not Found", "File not found: "+path);
									}
									
									
								}
							} else {
								sendResponse(out, "400 Bad Request", "Malformed request: "+line);
							}
							
						} catch (Exception e) {
							Log.e("BillyRobot","Exception", e);
							//handler.sendMessage(handler.obtainMessage(0, e.getClass()));
							sendResponse(out, "500 Internal Server Error", e.getStackTrace().toString());
						}
						client.close();
					}
				}
			} catch(Exception e) {
				handler.sendMessage(handler.obtainMessage(0, e.getClass() +" "+ e.getMessage()));
			}
		}
		
		private void sendResponse(PrintWriter out, String code, String body, String type) {
			out.println("HTTP/1.1 "+code);
			out.println("Content-Type: "+type);
			out.println("Content-Length: "+body.length());
			out.println();
			out.println(body);

			handler.sendMessage(handler.obtainMessage(0, code));
		}
		
		private void sendResponse(PrintWriter out, String code, String body) {
			sendResponse(out, code, body, "text/plain");
		}
		
	}

	class IOIOThread extends AbstractIOIOActivity.IOIOThread {
		private DigitalOutput stat;
		@SuppressWarnings("unused")
		private DigitalOutput stby;
		private IOIOMotor leftMotor;
		private IOIOMotor rightMotor;
				
		//Pin numbers
		final int pwmA = 4;
		final int a2 = 5;
		final int a1 = 6;
		final int standby = 7;
		final int b1 = 8;
		final int b2 = 9;
		final int pwmB = 10;
		
		
		@Override
		protected void setup() throws ConnectionLostException {
			stat = ioio_.openDigitalOutput(0, true);
			stby = ioio_.openDigitalOutput(7, true);
			
			leftMotor = new IOIOMotor(ioio_, pwmA, a2, a1);
			rightMotor = new IOIOMotor(ioio_, pwmB, b1, b2);
			
		}

		@Override
		protected void loop() throws ConnectionLostException {
			stat.write(false);
			leftMotor.setSpeed(leftSpeed);
			rightMotor.setSpeed(rightSpeed);
			try {
				sleep(100);
			} catch (InterruptedException e) {
			}
		}
	}

	@Override
	protected AbstractIOIOActivity.IOIOThread createIOIOThread() {
		return new IOIOThread();
	}

}