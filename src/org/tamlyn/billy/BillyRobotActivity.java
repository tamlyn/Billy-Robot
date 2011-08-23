package org.tamlyn.billy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import ioio.lib.api.*;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.AbstractIOIOActivity;

public class BillyRobotActivity extends AbstractIOIOActivity {
	
	private TextView status;
	private int statusMaxLines = 10;
	private Handler handler = new Handler() {
		public void handleMessage(Message msg) {
			status.append((String)msg.obj + "\n");
			String text = status.getText().toString();
			String[] lines = text.split("\n");
			if (lines.length > statusMaxLines) {
				String[] newLines = new String[statusMaxLines];
				System.arraycopy(lines, lines.length - statusMaxLines, newLines, 0, statusMaxLines);
				status.setText("");
				for (int i = 0; i < statusMaxLines; i++) {
					status.append(newLines[i]+"\n");
				}
			}
		}
	};
	private ServerSocket serverSocket;
	final int port = 8080;
	public static String ip;
	public float leftSpeed = 0;
	public float rightSpeed = 0;
	private Thread serverThread;
	private int requestCounter;
	private Camera camera;
	private Size previewSize;
	private byte[] imageData;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		ip = getLocalIpAddress();
		status = (TextView) findViewById(R.id.status);
		status.setText("");
		
		SurfaceView cameraSurface = (SurfaceView) findViewById(R.id.cameraSurface);
		SurfaceHolder holder = cameraSurface.getHolder();
		SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
			public void surfaceCreated(SurfaceHolder holder) {
				try {
					camera.setPreviewDisplay(holder);
					camera.startPreview();
				} catch (IOException e) {
					Log.e("Exception", e.toString());
				}
			}
			
			public void surfaceDestroyed(SurfaceHolder holder) {}
			public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
		};
		
		
		holder.addCallback(surfaceCallback);
		holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		
		/*try{
		      super.onCreate(savedInstanceState);
		      cv = new CustomCameraView(this.getApplicationContext());
		      FrameLayout rl = new FrameLayout(this.getApplicationContext());
		      setContentView(rl);
		      rl.addView(cv);
		} catch(Exception e){}*/
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
	protected void onResume() {
		super.onResume();
		serverThread = new Thread(new WebServer());
		serverThread.start();
		
		//open camera
		camera = Camera.open();
		camera.setDisplayOrientation(90);
		Camera.Parameters params = camera.getParameters();

		//List<Integer> formats = params.getSupportedPictureFormats();
		//Log.d("Format", formats.toString());
		//params.setPreviewFormat(ImageFormat.NV21);
		
		//find smallest preview size
		List<Size> sizes = params.getSupportedPreviewSizes();
		Iterator<Size> iter = sizes.iterator();
		previewSize = sizes.get(0);
		int minPixelCount = previewSize.width * previewSize.height;
		while (iter.hasNext()) {
			Size size = iter.next();
			int pixelCount = size.height * size.width;
			if (pixelCount < minPixelCount) {
				minPixelCount = pixelCount;
				previewSize = size;
			}
		}
		params.setPreviewSize(previewSize.width, previewSize.height);
		
		params.setRotation(90);
		
		camera.setParameters(params);
	
	}

	protected void onPause() {
		super.onPause();
		try {
			serverSocket.close();
		} catch (IOException e) {}
		camera.release();
	}

	public class WebServer implements Runnable {
		
		public void run() {
			try {
				serverSocket = new ServerSocket(port);
				handler.sendMessage(handler.obtainMessage(0, "Listening on "+ip+":"+port));
				
				while (true) {
					Socket client = serverSocket.accept(); //blocks
					@SuppressWarnings("unused")
					Thread connection = new ConnectionThread(client);
				}
			} catch(Exception e) {
				Log.e("BillyRobot","Exception", e);
			}
		}
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
				handler.sendMessage(handler.obtainMessage(0, line));
				
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
						leftSpeed = Float.parseFloat(queryMap.get("left"));
						rightSpeed = Float.parseFloat(queryMap.get("right"));
						
						if (Math.abs(leftSpeed) < 0.3) leftSpeed = 0;
						if (Math.abs(rightSpeed) < 0.3) rightSpeed = 0;
						
						if (Math.abs(leftSpeed) > 0.8) leftSpeed = leftSpeed > 0 ? 0.8f : -0.8f;
						if (Math.abs(rightSpeed) > 0.8) rightSpeed = rightSpeed > 0 ? 0.8f : -0.8f;
						
						sendResponse("200 OK", path);
					} else if (pathBits.length > 1 && pathBits[1].equals("sensor")) {
						//get sensor data
						imageData = null;
						
						camera.setOneShotPreviewCallback(new PreviewCallback() {
							public void onPreviewFrame(byte[] data, Camera camera) {
								imageData = data;
							}
						});
						
						//wait for image data
					    while(imageData == null) {
					    	sleep(10);
					    }
					    
					    //Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
					    //Matrix matrix = new Matrix();
					    //matrix.postRotate(90);
					    //Bitmap rotatedBMP = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
					    //ByteArrayOutputStream baos=new ByteArrayOutputStream();
					    //bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
					    
					    YuvImage yuv = new YuvImage(imageData, ImageFormat.NV21, previewSize.width, previewSize.height, null);
					    Rect rect = new Rect(0, 0, previewSize.width, previewSize.height);
					    ByteArrayOutputStream baos=new ByteArrayOutputStream();
					    yuv.compressToJpeg(rect, 70, baos);
					    
					    sendHeaders("200 OK", "image/jpeg", baos.size());
						outStream.write(baos.toByteArray());
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
						if (ext.equals("ico")) mimeType = "image/x-icon";
						//if (ext.equals("png")) mimeType = "image/png";
						
						//read file into a string
						AssetManager assetManager = getResources().getAssets();
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
				Log.e("BillyRobot","Exception", e);
			}
			
		}
		
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

	class IOIOThread extends AbstractIOIOActivity.IOIOThread {
		private DigitalOutput stat;
		@SuppressWarnings("unused")
		private DigitalOutput stby;
		private IOIOMotor leftMotor;
		private IOIOMotor rightMotor;
				
		//Pin numbers
		final int pwmA = 4;
		final int a2 = 6;
		final int a1 = 5;
		final int standby = 7;
		final int b1 = 9;
		final int b2 = 8;
		final int pwmB = 10;
		
		
		@Override
		protected void setup() throws ConnectionLostException {
			stat = ioio_.openDigitalOutput(0, true);
			stby = ioio_.openDigitalOutput(standby, true);
			
			leftMotor = new IOIOMotor(ioio_, pwmB, b1, b2);
			rightMotor = new IOIOMotor(ioio_, pwmA, a2, a1);
			
		}

		@Override
		protected void loop() throws ConnectionLostException {
			stat.write(false);
			leftMotor.setSpeed(leftSpeed);
			rightMotor.setSpeed(rightSpeed);
			try {
				sleep(10);
			} catch (InterruptedException e) {
			}
		}
	}

	@Override
	protected AbstractIOIOActivity.IOIOThread createIOIOThread() {
		return new IOIOThread();
	}

}