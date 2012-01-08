package org.tamlyn.billy;

import ioio.lib.api.DigitalOutput;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.AbstractIOIOActivity;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.ToggleButton;

public class BillyRobotActivity extends AbstractIOIOActivity {
	
	private TextView status;
	private SimpleWebServer server;
	public float leftSpeed = 0;
	public float rightSpeed = 0;
	private Camera camera;
	private Size previewSize;
	private byte[] imageData;
	private DrawView drawView;
	private SurfaceView cameraSurface;
	private boolean drawingView = false;
	private ToggleButton autoButton;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		
		//setup tabs
		TabHost tabs = (TabHost) this.findViewById(R.id.tabhost);
		tabs.setup();
		tabs.addTab(tabs.newTabSpec("Camera").setIndicator("Camera").setContent(R.id.tab2));
		tabs.addTab(tabs.newTabSpec("Log").setIndicator("Log").setContent(R.id.tab1));
		
		//setup augmented overlay
		FrameLayout fl = (FrameLayout) this.findViewById(R.id.tab2);
		drawView = new DrawView(this);
		fl.addView(drawView);
		autoButton = (ToggleButton) this.findViewById(R.id.autobutton);
		
		//setup web server
		Handler handler = new Handler() {
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case 1 :
					logMessage((String)msg.obj);
					break;
				case 2 :
					leftSpeed = 1.0f*msg.arg1/1000;
					rightSpeed = 1.0f*msg.arg2/1000;
					break;
				}
			}
		};
		server = new SimpleWebServer(getResources().getAssets(), handler);
		server.start();
		
		//setup log screen
		status = (TextView) findViewById(R.id.status);
		status.setText("");
		
		//setup camera surface		
		cameraSurface = (SurfaceView) findViewById(R.id.cameraSurface);
		SurfaceHolder cameraHolder = cameraSurface.getHolder();
		cameraHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		cameraHolder.addCallback(new SurfaceHolder.Callback() {
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
		});
		
		
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		
		//server.start();
		
		//open camera
		camera = Camera.open();
		camera.setDisplayOrientation(90);
		Camera.Parameters params = camera.getParameters();
		
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
		
//		ViewGroup.LayoutParams layoutParams = cameraSurface.getLayoutParams();
//		layoutParams.height = previewSize.width;
//		layoutParams.width = previewSize.height;
//		cameraSurface.setLayoutParams(layoutParams);
		
		drawView.setPreviewSize(previewSize.width, previewSize.height);
		imageData = new byte[previewSize.width*previewSize.height];
		camera.setPreviewCallback(new Camera.PreviewCallback() {
			
			@Override
			public void onPreviewFrame(byte[] data, Camera camera) {
				if (!drawingView) {
					imageData = data;
					drawView.invalidate();
					drawingView = true;
				}
			}
		});
		
		params.setRotation(90);
		camera.setParameters(params);
	
	}
	
	protected void onPause() {
		super.onPause();
		camera.release();
	}

	private void logMessage(String message) {
		status.setText(message + "\n" + status.getText());
	}
	
	public class DrawView extends View {
		/*
		 * Vanishing point analysis
		 * See http://scholar.lib.vt.edu/theses/available/etd-283421290973280/unrestricted/etd.pdf
		 * pages 42-49 for the mathematics  
		 */
		public static final double TAN_THETA = 7.5/21, 
			IMAGE_D = 15, //distance from centre of vehicle to bottom of image plane 
			IMAGE_A = 5; //distance from bottom centre of image plane to bottom edge
		private int imageWidth, imageHeight, horizonOffset = 16;
		int[] localData, edgePixels, houghSpace;
		public static final int PIXEL_THRESHOLD = 128, HOUGH_THRESHOLD = 64, 
			HOUGH_C_SIZE = 64, HOUGH_M_SIZE = 32,
			HOUGH_C_SCALE = 4, HOUGH_C_OFFSET = -16;
		private long lastTime;
		
		
		public DrawView(Context context) {
			super(context);
			
			lastTime = System.nanoTime();
		}
		
		public void setPreviewSize(int width, int height) {
			ViewGroup.LayoutParams layoutParams = this.getLayoutParams();
			layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
			layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
			this.setLayoutParams(layoutParams);
			
			imageWidth = width;
			imageHeight = height;
			
			localData = new int[width*height];
			edgePixels = new int[width*height];
		}
		
		/*
		 * Called by system when view is invalidated
		 */
		@Override
	    public void onDraw(Canvas canvas) {
	        super.onDraw(canvas);
	        if (imageData == null) return;
	        
	        for (int i=imageWidth*imageHeight-1; i>=0; i--) {
	        	localData[i] = imageData[i] & 0xff;
	        }
	        houghSpace = new int[HOUGH_C_SIZE*HOUGH_M_SIZE];

	        for(int i=1; i<imageHeight-1; i++) {
	        	int maxWidth = imageWidth*(i+1)-1;
	        	for(int j=imageWidth*i+1;j<maxWidth;j++) {
	                //calculate pixel differences
	        		int value = localData[j-imageWidth]*2 - localData[j+imageWidth]*2
                    	+ localData[j-1-imageWidth] + localData[j+1-imageWidth]
                        - localData[j-1+imageWidth] - localData[j+1+imageWidth];
              
	        		if(value<0) value = -value; //positivise
	                if(value>255) value=255; //avoid clipping
	                
	                //hough stuff
	                if(value>PIXEL_THRESHOLD) {
	                    int y = i, x = j - imageWidth*i-1;
	                    for (int mf=0;mf<HOUGH_M_SIZE;mf++) {
	                    	double m = 2.0*mf/HOUGH_M_SIZE-1;
	                    	int cf = (int) (y - m*x)/HOUGH_C_SCALE - HOUGH_C_OFFSET;
	                    	if (cf >= 0 && cf < HOUGH_C_SIZE) {
	                    		houghSpace[HOUGH_C_SIZE*mf+cf]++;
	                    	}
	                    }
	                }
	                
	                //convert back to RGBA
	                edgePixels[j] = value | (value << 8) | (value << 16) | (255 << 24);
	            }
	        }
	        
	        int maxVal = houghSpace[0], maxPos = 0;
	        for(int k=1;k<houghSpace.length;k++) {
	            if(houghSpace[k] > maxVal) {
	                maxPos = k;
	                maxVal = houghSpace[k];
	            }
	        }
	        
	        //render edges
//	        Bitmap bitmap = Bitmap.createBitmap(edgePixels, width, height, Bitmap.Config.ARGB_8888);
//	        Matrix matrix = new Matrix();
//	        matrix.setRotate(90, height/2, height/2);
//	        canvas.drawBitmap(bitmap, matrix, null);
//	        canvas.rotate(90, height/2, height/2);
//	        canvas.drawBitmap(edgePixels, 0, width, 0, 0, width, height, false, null);

	        //render hough space
//	        float scale = maxVal>0 ? 255/maxVal : 1;
//	        for(int k=0;k<houghSpace.length;k++) {
//	            int value = (int) (houghSpace[k] * scale);
//	            houghSpace[k] = value | value<<8 | value<<16 | 0xff000000;
//	        }
//	        Bitmap houghMap = Bitmap.createBitmap(houghSpace, HOUGH_C_SIZE, HOUGH_M_SIZE, Bitmap.Config.ARGB_8888);
//	        Matrix hmatrix = new Matrix();
//	        hmatrix.setTranslate(0, width+10);
//	        canvas.drawBitmap(houghMap, hmatrix, null);
	        
	        
			
	        Paint paint = new Paint();
	        paint.setColor(Color.RED);
	        
	        //draw horizon line
	        float scaledOffset = horizonOffset * this.getHeight() / imageWidth;
	        canvas.drawLine(0, scaledOffset, this.getWidth(), scaledOffset, paint);
	        
	        paint.setStrokeWidth(2);
	        paint.setColor(Color.GREEN);
	        
	        int interceptTop = imageHeight - HOUGH_C_SCALE * (maxPos % HOUGH_C_SIZE + HOUGH_C_OFFSET);
	        float linem = (float) (2*Math.floor(maxPos/HOUGH_C_SIZE)/HOUGH_M_SIZE - 1),
	        	interceptHorizon = interceptTop - linem*horizonOffset,
	        	interceptBottom = interceptTop - linem*imageWidth;
	        
	        int midPoint = imageHeight/2;
	        double heading = Math.atan((interceptHorizon*TAN_THETA)/midPoint-TAN_THETA);
	        
	        double aReal = (interceptBottom-midPoint) * IMAGE_A/midPoint;
	        double drift = aReal*Math.cos(heading) - IMAGE_D*Math.sin(heading);
	        
	        float xScale = 1.0f*this.getWidth()/imageHeight, yScale = 1.0f*this.getHeight()/imageWidth;
        
			float Kh = 1f, Kd = 0.01f, headingSign = heading < 0 ? -1 : 1,
				error = (float) (heading*heading*Kh*headingSign + drift*Kd);
			
			if (error > 0.1) error = 0.1f;
			if (error < -0.1) error = -0.1f;
			
	        leftSpeed = 0;
	        rightSpeed = 0;

	        if (maxVal > HOUGH_THRESHOLD) {
	        
		        canvas.drawLine(interceptHorizon*xScale, horizonOffset*yScale, 
		        		interceptBottom*xScale, imageWidth*yScale, paint);
		        
		        if (autoButton.isChecked()) {
			        leftSpeed = 0.25f;
			        rightSpeed = 0.25f;

			        leftSpeed += error;
			        rightSpeed -= error;
		        }
		         
	        }
	        
	        paint.setColor(Color.RED);
	        paint.setTextSize(30);
	        
	        long thisTime = System.nanoTime();
	        double fps = Math.floor(1e12/(thisTime - lastTime))/1000;
	        lastTime = thisTime;
	        String text = "" + fps + " fps";
	        canvas.drawText(text, 5, 30, paint);
	        canvas.drawText("Heading "+(Math.floor(heading*1000)/1000), 5, 60, paint);
	        canvas.drawText("Drift "+(Math.floor(drift*1000)/1000), 5, 90, paint);
	        canvas.drawText("Error "+(Math.floor(error*1000)/1000), 5, 120, paint);
	        
	        drawingView = false;
	        
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