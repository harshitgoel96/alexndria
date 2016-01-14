package it.jaschke.alexandria.CameraPreview;

/*
 * Barebones implementation of displaying camera preview.
 *
 * Created by lisah0 on 2012-02-24
 */

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import butterknife.Bind;
import butterknife.ButterKnife;
import it.jaschke.alexandria.R;
import it.jaschke.alexandria.UtillProvider;

/** A complete rework of the camera preview class using Camera2 api and anroid Vison api to get the
 * Barcode from image.*/
public class CameraPreview extends Activity {

    //cameraPreview
    @Bind(R.id.cameraPreview)
    TextureView cameraPreview;

    @Bind(R.id.checkBarcode)
    Button clickImage;


    private CameraManager cameraManager;
    private CameraDevice theCamera;
    private CaptureRequest mCaptureRequest;
    private CaptureRequest.Builder mCaptureReqBuilder;
    BarcodeDetector detector;
    private ImageReader imgReader;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    private int mState = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    ExecutorService exec= Executors.newFixedThreadPool(3);
    private ImageReader.OnImageAvailableListener barcodeChecker = new
            ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {


                    // call barcode here
                    Log.e("IMAGE READER", "IT IS AVAILABLE TO READ BARCODE");
//                    BarcodeDetectionThread bckThred = new BarcodeDetectionThread(reader.acquireLatestImage(),detector);
//                    bckThred.run();
//                    mHandler.post(
//                            new BarcodeDetectionThread(reader.acquireLatestImage(), detector)
//                    );


                    try {
                        Image temImg=reader.acquireNextImage();

                        Callable<String> caller=new BarcodeCallable(temImg, detector);
                        Future<String> futureData= exec.submit(caller);
                        mBarcode=futureData.get();
                        Log.e("OUTSIDE", "value:" + mBarcode);
                        temImg.close();
                        if(mBarcode==null){
                            showTost("No Barcode found, try again");
                        }
                        else{
//                            showTost("Barcode is "+mBarcode+" possible to return result from here");
                            Intent resultIntent = new Intent();
                            resultIntent.putExtra(UtillProvider.returnKey,mBarcode);
// TODO Add extras or a data URI to this intent as appropriate.
                            setResult(Activity.RESULT_OK, resultIntent);
                            finish();
                        }

                    }
                    catch (Exception e){

                    }
                    finally {

                        //reader.close();
                    }
                    //reader.close();
                }
            };
    private static String mBarcode = "";


    static class BarcodeCallable implements Callable<String> {
        private Image mImage;
        private BarcodeDetector internaldetector;

        BarcodeCallable(){

        }
        BarcodeCallable(Image img, BarcodeDetector detector) {
            mImage = img;
            internaldetector = detector;
        }
        @Override
        public String call() throws Exception {
            if (null != mImage) {
                Frame frm = new Frame.Builder().setImageData(mImage.getPlanes()[0].getBuffer(), mImage.getWidth(), mImage.getHeight(), ImageFormat.YV12).build();
                SparseArray<Barcode> barcodes = internaldetector.detect(frm);
                for (int i = 0; i < barcodes.size(); i++) {
                    if (barcodes.valueAt(i).rawValue.length() == 10 || barcodes.valueAt(i).rawValue.length() == 13) {
                        mImage.close();
                        return       barcodes.valueAt(i).rawValue;
                    }
                }
            }
            return null;
        }
    }

    //Runnable Thread to process each image frame for 10 images
    static class BarcodeDetectionThread implements Runnable {
        private Image mImage;
        private BarcodeDetector internaldetector;

        BarcodeDetectionThread() {

        }

        BarcodeDetectionThread(Image img, BarcodeDetector detector) {
            mImage = img;
            internaldetector = detector;
        }

        @Override
        public void run() {
            if (internaldetector.isOperational()) {
                if (null != mImage) {
                    // ByteBuffer imgBuffr=
                    Log.e("IMG", "format : " + mImage.getFormat() + "  |  SIZE " + (mImage.getPlanes()).length);
                    Frame frm = new Frame.Builder().setImageData(mImage.getPlanes()[0].getBuffer(), mImage.getWidth(), mImage.getHeight(), ImageFormat.YV12).build();
                    SparseArray<Barcode> barcodes = internaldetector.detect(frm);
                    for (int i = 0; i < barcodes.size(); i++) {
                        if (barcodes.valueAt(i).rawValue.length() == 10 || barcodes.valueAt(i).rawValue.length() == 13) {
                            mBarcode = barcodes.valueAt(i).rawValue;
                            Log.e("BARCODE", mBarcode);
                        }

                    }
                } else {
                    Log.e("ImageReader", "NULL Image");
                }
            } else {
                Log.e("BARCODEERR", "Barcode not usable");
            }
            mImage.close();
            //internaldetector.s
        }
    }


    private CameraDevice.StateCallback cameraStateCallback = new
            CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    theCamera = camera;
                    openCameraPreview();
                    mCameraOpenCloseLock.release();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    mCameraOpenCloseLock.release();
                    camera.close();
                    theCamera = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    mCameraOpenCloseLock.release();
                    camera.close();
                    theCamera = null;
                }
            };
    private String mCameraId;
    private TextureView.SurfaceTextureListener mSurfaceListener = new
            TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    setupCamera(width, height);
                    startCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {

                }
            };

    private CameraCaptureSession mCameraCaptureSession;
    private CameraCaptureSession.CaptureCallback mCameraSessionCallback =
            new CameraCaptureSession.CaptureCallback() {
                private void process(CaptureResult result) {
                    switch (mState) {
                        case STATE_PREVIEW: {
                            // We have nothing to do when the camera preview is working normally.
//                            int aeState=result.get(CaptureResult.CONTROL_AF_STATE);
//                            if(aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE)
//                            takePicture();

                            break;
                        }
                        case STATE_WAITING_LOCK: {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                captureStillPicture();
                            } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                                // CONTROL_AE_STATE can be null on some devices
                                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                                if (aeState == null ||
                                        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                    mState = STATE_PICTURE_TAKEN;
                                    captureStillPicture();
                                } else {
                                    runPrecaptureSequence();
                                }
                            }
                            break;
                        }
                        case STATE_WAITING_PRECAPTURE: {
                            // CONTROL_AE_STATE can be null on some devices
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null ||
                                    aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                                    aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                                mState = STATE_WAITING_NON_PRECAPTURE;
                            }
                            break;
                        }
                        case STATE_WAITING_NON_PRECAPTURE: {
                            // CONTROL_AE_STATE can be null on some devices
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                mState = STATE_PICTURE_TAKEN;
                                captureStillPicture();
                            }
                            break;
                        }
                    }
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                                @NonNull CaptureRequest request,
                                                @NonNull CaptureResult partialResult) {
                    process(partialResult);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    process(result);
                }
            };
    private Size prefferedBufferSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.camera_preview);
        ButterKnife.bind(this);
        clickImage.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        takePicture();
                    }
                }
        );
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        detector = new BarcodeDetector.Builder(getApplicationContext()).build();
    }

    private void setupCamera(int width, int height) {

        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                if ((characteristics.get(CameraCharacteristics.LENS_FACING)).equals(CameraCharacteristics.LENS_FACING_BACK)) {
                    StreamConfigurationMap configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    Size largestImageSize = Collections.max(
                            Arrays.asList(configMap.getOutputSizes(ImageFormat.YUV_420_888)),
                            new Comparator<Size>() {
                                @Override
                                public int compare(Size lhs, Size rhs) {
                                    return Long.signum(
                                            lhs.getWidth() * lhs.getWidth() - rhs.getHeight() * rhs.getWidth()
                                    );
                                    //return 0;
                                }
                            }
                    );

                    imgReader = ImageReader.newInstance(largestImageSize.getWidth(), largestImageSize.getHeight(), ImageFormat.YUV_420_888, 60*600);
                    imgReader.setOnImageAvailableListener(barcodeChecker, mHandler);
                    prefferedBufferSize = getBestSize(configMap.getOutputSizes(SurfaceTexture.class), width, height);
                    mCameraId = cameraId;
                    return;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        openBackgroundThread();
        if (cameraPreview.isAvailable()) {
            setupCamera(cameraPreview.getWidth(), cameraPreview.getHeight());
            startCamera();
        } else {
            cameraPreview.setSurfaceTextureListener(mSurfaceListener);
        }
    }

    @Override
    public void onPause() {

        shutDownCamera();
        closeBackgroundThread();
        super.onPause();
    }

    private void shutDownCamera() {
        try{
        mCameraOpenCloseLock.acquire();
        if (mCameraCaptureSession != null) {
            mCameraCaptureSession.close();
            mCameraCaptureSession = null;
        }
        if (theCamera != null) {
            theCamera.close();
            theCamera = null;
        }
        if (imgReader != null) {
            imgReader.close();
            imgReader = null;
        }
        }    catch(Exception e){
            e.printStackTrace();
        }
        finally {
            mCameraOpenCloseLock.release();
        }

    }

    private void startCamera() {
        try {

            if (hasCameraPermission()) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    showTost("Can not access camera. Permission Denied");
                    return;
                }
                try {
                    cameraManager.openCamera(mCameraId, cameraStateCallback, mHandler);
                } catch (SecurityException accessExc) {
                    accessExc.printStackTrace();
                    showTost("Could not access Camera, check app permissions");
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void openCameraPreview() {
        try {
            SurfaceTexture surfaceTexture = cameraPreview.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(prefferedBufferSize.getWidth(), prefferedBufferSize.getHeight());
            Surface theView = new Surface(surfaceTexture);
            mCaptureReqBuilder = theCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureReqBuilder.addTarget(theView);

            theCamera.createCaptureSession(
                    Arrays.asList(theView, imgReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (theCamera == null) return;
                            //return at above line
                            try {
                                mCaptureRequest = mCaptureReqBuilder.build();
                                mCameraCaptureSession = session;
                                mCameraCaptureSession.setRepeatingRequest(
                                        mCaptureRequest,
                                        mCameraSessionCallback, mHandler
                                );

                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            showTost("Session creation failed");
                        }
                    }, null
            );//end of create capture session
        } catch (Exception e) {
        }
    }

    private boolean hasCameraPermission() {
        PackageManager pm = getPackageManager();
        int hasPerm = pm.checkPermission(
                Manifest.permission.CAMERA,
                getPackageName());
        return hasPerm == PackageManager.PERMISSION_GRANTED;
    }

    void showTost(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private Size getBestSize(Size[] sizes, int width, int height) {
        List<Size> sizesList = new ArrayList<>();
        for (Size option : sizes) {
            if (width > height) {
                if (option.getWidth() > width && option.getHeight() > height)
                    sizesList.add(option);
            } else {
                if (option.getHeight() > width
                        && option.getWidth() > height)
                    sizesList.add(option);

            }
        }
        if (sizesList.size() > 0) {
            return Collections.min(sizesList,
                    new Comparator<Size>() {
                        @Override
                        public int compare(Size lhs, Size rhs) {
                            return Long.signum(lhs.getWidth() * lhs.getHeight() - rhs.getWidth() * rhs.getHeight());
                        }
                    }
            );
        }
        return sizes[0];
    }

    private HandlerThread backHandlerThread;
    private Handler mHandler;

    private void openBackgroundThread() {
        backHandlerThread = new HandlerThread("Camera Background Thread");
        backHandlerThread.start();
        mHandler = new Handler(backHandlerThread.getLooper());
    }

    private void closeBackgroundThread() {
        backHandlerThread.quitSafely();
        try {
            backHandlerThread.join();
            backHandlerThread = null;
            mHandler = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            mCaptureReqBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            mCameraCaptureSession.capture(mCaptureReqBuilder.build(), mCameraSessionCallback,
                    mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void takePicture() {
        lockFocus();
    }

    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            if(null!=mCaptureReqBuilder && null!= mCameraCaptureSession && mCameraSessionCallback!=null){
            mCaptureReqBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCameraCaptureSession.capture(mCaptureReqBuilder.build(), mCameraSessionCallback,
                    mHandler);}
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void unlockFocus() {
        try {
            // Reset the auto-focus trigger
            if(null!=mCaptureReqBuilder && null!= mCameraCaptureSession && mCameraSessionCallback!=null){
            mCaptureReqBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mCaptureReqBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            mCameraCaptureSession.capture(mCaptureReqBuilder.build(), mCameraSessionCallback,
                    mHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mCameraCaptureSession.setRepeatingRequest(mCaptureRequest, mCameraSessionCallback,
                    mHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void captureStillPicture() {
        try {
            final Activity activity = this;
            if (null == activity || null == theCamera) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    theCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imgReader.getSurface());

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    //showTost("Saved: " + mFile);
                    //Log.d(TAG, mFile.toString());
                    unlockFocus();
                }
            };

            mCameraCaptureSession.stopRepeating();
            mCameraCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }


    }

}
