package com.jjoe64.motiondetection;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES11Ext;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


public class MotionDetector {
    class MotionDetectorThread extends Thread {
        private AtomicBoolean isRunning = new AtomicBoolean(true);

        public void stopDetection() {
            Log.i(TAG, "stopping detection");
            isRunning.set(false);
        }

        @Override
        public void run() {
            AtomicReference<com.jjoe64.motiondetection.State> oldState = new AtomicReference<>();
            // skip first checks, to let the camera settle
            for(int i=0; i<6; i++){
                try {
                    Log.d(TAG, "Startup, skipping analysis");
                    mCamera.addCallbackBuffer(imageBuffer);
                    Thread.sleep(checkInterval);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            while (isRunning.get()) {
                long now = System.currentTimeMillis();
                if (now - lastCheck > checkInterval) {
                    Log.d(TAG, "processing next image");
                    lastCheck = now;

                    if (imageBuffer != null && imageBuffer.length != 0) {
                        if (nextWidth.get() > 1 && nextHeight.get() > 1) {

                            Log.d(TAG, "start image analysis");
                            int[] img = ImageProcessing.decodeYUV420SPtoLuma(imageBuffer, nextWidth.get(), nextHeight.get());
                            com.jjoe64.motiondetection.State newState = new com.jjoe64.motiondetection.State(img, nextWidth.get(), nextHeight.get());


                            // check if it is too dark
                            int lumaSum = 0;
                            for (int i : img) {
                                lumaSum += i;
                            }
                            Log.d(TAG, "motionDetect  image analysis");
                            if (lumaSum < minLuma) {
                                Log.i(TAG, "too dark");
                                if (motionDetectorCallback != null) {
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            motionDetectorCallback.onTooDark();
                                        }
                                    });
                                }
                            } else if (detector.detect(oldState.get(), newState))
                                if (motionDetectorCallback != null) {
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            motionDetectorCallback.onMotionDetected();
                                        }
                                    });
                                }
                            oldState.set(newState);
                        }

                        mCamera.addCallbackBuffer(imageBuffer);
                        Log.d(TAG, "done image analysis");
                    }
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            // analysis was stopped, release the camera
            releaseCamera();
        }
    }

    private final String TAG = MotionDetector.class.getName();
    private final AggregateLumaMotionDetection detector;
    private long checkInterval = 500;
    private long lastCheck = 0;
    private MotionDetectorCallback motionDetectorCallback;
    private Handler mHandler = new Handler();

    private AtomicInteger nextWidth = new AtomicInteger();
    private AtomicInteger nextHeight = new AtomicInteger();
    private int minLuma = 1000;
    private MotionDetectorThread worker;

    private Camera mCamera;
    private boolean inPreview;
    private SurfaceHolder previewHolder;
    private Context mContext;
    private SurfaceView mSurface;
    SurfaceTexture surfaceTexture;
    private byte[] imageBuffer;

    public MotionDetector(Context context, SurfaceView previewSurface) {
        detector = new AggregateLumaMotionDetection();
        mContext = context;
        mSurface = previewSurface;
    }

    public void setMotionDetectorCallback(MotionDetectorCallback motionDetectorCallback) {
        this.motionDetectorCallback = motionDetectorCallback;
    }

/*    public void consume(byte[] data, int width, int height) {
        nextWidth.set(width);
        nextHeight.set(height);
    }*/

    public void setCheckInterval(long checkInterval) {
        this.checkInterval = checkInterval;
    }

    public void setMinLuma(int minLuma) {
        this.minLuma = minLuma;
    }

    public void setLeniency(int l) {
        detector.setLeniency(l);
    }

    public void onResume() {
        if (checkCameraHardware()) {
            mCamera = getCameraInstance();
            if(mCamera == null){
                Log.e(TAG, "Motion detection disabled since no camera available");
                return;
            }

            worker = new MotionDetectorThread();
            worker.start();

            // configure preview
            if(mSurface!=null) {
                previewHolder = mSurface.getHolder();
                previewHolder.addCallback(surfaceCallback);
                previewHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
            } else {
                try {
                    //List<Camera.Size> sizes = mCamera.getParameters().getSupportedPreviewSizes();
                    Camera.Size firstSize = smallestPreviewFormat(mCamera.getParameters().getSupportedPreviewSizes());
                    Log.d(TAG, "Preview size = "+firstSize.width+" x "+firstSize.height);
                    nextWidth.set(firstSize.width);
                    nextHeight.set(firstSize.height);
                    
                    surfaceTexture = new SurfaceTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
                    surfaceTexture.setDefaultBufferSize(firstSize.width,firstSize.height);

                    mCamera.setPreviewTexture(surfaceTexture);
                    mCamera.setPreviewCallbackWithBuffer(previewCallback);

                    imageBuffer = new byte[imageBufferSize(mCamera.getParameters())];
                    mCamera.addCallbackBuffer(imageBuffer);

                    mCamera.getParameters().setPreviewSize(firstSize.width, firstSize.height);
                    mCamera.startPreview();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    private Camera.Size smallestPreviewFormat(List<Camera.Size> sizes){
        TreeMap<Integer, Camera.Size> sizeSortedMap = new TreeMap<>();
        for (Camera.Size size: sizes) {
            sizeSortedMap.put(size.width*size.height, size);
        }
        return  sizeSortedMap.firstEntry().getValue();
    }

    private int imageBufferSize(Camera.Parameters parameters){
        Camera.Size size = parameters.getPreviewSize();
        return (size.width
                * size.height
                * android.graphics.ImageFormat.getBitsPerPixel(parameters.getPreviewFormat())
        ) / 8;

    }

    public boolean checkCameraHardware() {
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)){
            // this device has a camera
            return true;
        } else {
            // no camera on this device
            return false;
        }
    }

    private Camera getCameraInstance(){
        Camera c = null;

        try {
            if (Camera.getNumberOfCameras() >= 2) {
                //if you want to open front facing camera use this line
                c = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
            } else {
                c = Camera.open();
            }
        }
        catch (Exception e){
            // Camera is not available (in use or does not exist)
            //txtStatus.setText("Kamera nicht zur Benutzung freigegeben");
        }
        return c; // returns null if camera is unavailable
    }

    private Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {

        /**
         * {@inheritDoc}
         */
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            Log.d(TAG,"received preview frame");
            if (data == null) return;
            Camera.Size size = cam.getParameters().getPreviewSize();
            if (size == null) return;

            //consume(data, size.width, size.height);
        }
    };


    private SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                mCamera.setPreviewDisplay(previewHolder);
                mCamera.setPreviewCallback(previewCallback);
            } catch (Throwable t) {
                Log.e("MotionDetector", "Exception in setPreviewDisplay()", t);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Camera.Parameters parameters = mCamera.getParameters();
            Camera.Size size = getBestPreviewSize(width, height, parameters);
            if (size != null) {
                parameters.setPreviewSize(size.width, size.height);
                Log.d("MotionDetector", "Using width=" + size.width + " height=" + size.height);
            }
            mCamera.setParameters(parameters);
            mCamera.startPreview();
            inPreview = true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // Ignore
        }
    };

    private static Camera.Size getBestPreviewSize(int width, int height, Camera.Parameters parameters) {
        Camera.Size result = null;

        for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
            if (size.width <= width && size.height <= height) {
                if (result == null) {
                    result = size;
                } else {
                    int resultArea = result.width * result.height;
                    int newArea = size.width * size.height;

                    if (newArea > resultArea) result = size;
                }
            }
        }

        return result;
    }



    public void onPause() {
        if (previewHolder != null) previewHolder.removeCallback(surfaceCallback);
        if (worker != null) worker.stopDetection();
       // releaseCamera();
    }

    private void releaseCamera(){
        if (mCamera != null){
            mCamera.setPreviewCallback(null);
            if (inPreview) mCamera.stopPreview();
            inPreview = false;
            mCamera.release();        // release the camera for other applications
            mCamera = null;
        }
        Log.d(TAG,"Camera released");
    }
}
