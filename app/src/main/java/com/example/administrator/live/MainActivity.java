package com.example.administrator.live;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.example.administrator.live.R;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class MainActivity extends Activity implements View.OnClickListener {
    private SurfaceView mSurfaceView = null;
    private SurfaceHolder mSurfaceHolder = null;
    private ImageView mImageView = null;
    private Button mCaptureButton, mSwitchButton, mVideoButton;
    private CameraManager mCameraManager = null;
    private CameraDevice mCameraDevice = null;
    private CameraCaptureSession mCameraCaptureSession = null;
    private MediaRecorder mMediaRecorder = null;
    private String mCameraId = null;
    private Handler mCameraHandler, mMainHandler;
    private ImageReader mImageReader = null;
    private CaptureRequest.Builder mBuilder = null;
    private CaptureRequest mPreviewRequest = null;
    private boolean isVideo = false;
    private boolean isSwitch = false;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initUI();
    }
    private void initUI() {
        mSurfaceView = findViewById(R.id.camera_view);
        mSurfaceView.setOnClickListener(this);
        mImageView = findViewById(R.id.image_view);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.setKeepScreenOn(true);
        mSurfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                initCamera();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }


            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (mCameraDevice != null) {
                    mCameraDevice.close();
                    mCameraDevice = null;
                }
            }
        });
        mCaptureButton = findViewById(R.id.capture_button);
        mSwitchButton = findViewById(R.id.switch_button);
        mVideoButton = findViewById(R.id.video_button);
        mCaptureButton.setOnClickListener(this);
        mSwitchButton.setOnClickListener(this);
        mVideoButton.setOnClickListener(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void initCamera() {
        HandlerThread handlerThread = new HandlerThread("Camera2");
        handlerThread.start();
        mCameraHandler = new Handler(handlerThread.getLooper());
        mMainHandler = new Handler(getMainLooper());
        mCameraId = "" + CameraCharacteristics.LENS_FACING_FRONT;
        mImageReader = ImageReader.newInstance(1080, 1920, ImageFormat.JPEG, 1);
        mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                mCameraDevice.close();
                mSurfaceView.setVisibility(View.GONE);
                mImageView.setVisibility(View.VISIBLE);
                Image image = reader.acquireNextImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    mImageView.setImageBitmap(bitmap);
                }
            }
        }, mMainHandler);
        openCamera();
    }
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void openCamera(){
        mCameraManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if(isSwitch){
                mCameraId = "" + CameraCharacteristics.LENS_FACING_BACK;
            }else{
                mCameraId = "" + CameraCharacteristics.LENS_FACING_FRONT;
            }
            mCameraManager.openCamera(mCameraId, stateCallback, mMainHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            takePreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d("liangpan","========disconnected");
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Toast.makeText(MainActivity.this, "打开相机失败", Toast.LENGTH_LONG).show();
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void takePreview() {
        try {
            if (mCameraDevice == null) {
                return;
            }
            mBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mBuilder.addTarget(mSurfaceHolder.getSurface());
            List<Surface> surfaces = Arrays.asList(mSurfaceHolder.getSurface(), mImageReader.getSurface());
            if(isVideo){
                mBuilder.addTarget(mSurfaceHolder.getSurface());
                Surface previewSurface = mSurfaceHolder.getSurface();
                surfaces = Collections.singletonList(previewSurface);
            }
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (null == mCameraDevice) return;
                    // 当摄像头已经准备好时，开始显示预览
                    mCameraCaptureSession = session;
                    if(isVideo){
                        updatePreview();
                    }else {
                        try {
                            mBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            mPreviewRequest = mBuilder.build();
                            mCameraCaptureSession.setRepeatingRequest(mPreviewRequest,
                                    null, mCameraHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this,"配置失败",Toast.LENGTH_LONG).show();
                }
            }, mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private String mNextVideoAbsolutePath;
    private void initAudioManager(){
        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
            mNextVideoAbsolutePath = getVideoFilePath(this);
        }
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        try {
            mMediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onClick(View v) {
        switch(v.getId()){
            case R.id.capture_button:
                if(isVideo){
                    closeCamera();
                    mSurfaceHolder = mSurfaceView.getHolder();
                    openCamera();
                    isVideo = false;
                }
                takePicture();
                break;
            case R.id.switch_button:
                if(isSwitch) {
                    isSwitch = false;
                }else{
                    isSwitch = true;
                }
                closeCamera();
                openCamera();
                break;
            case R.id.video_button:
                if(!isVideo){
                    mSurfaceView.setVisibility(View.VISIBLE);
                    mImageView.setVisibility(View.GONE);
                    closeCamera();
                    openCamera();
                    isVideo = true;
                    startRecordingVideo();
                    mVideoButton.setText(R.string.stop_record);
                }else{
                    isVideo = false;
                    mVideoButton.setText(R.string.video_name);
                    stopRecordingVideo();
                }
                break;
        }
    }
    /**
     * 拍照
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void takePicture() {
        if (mCameraDevice == null) return;
        // 创建拍照需要的CaptureRequest.Builder
        try {
            closePreviewSession();
            stopRecordingVideo();
            mBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            // 将imageReader的surface作为CaptureRequest.Builder的目标
            mBuilder.addTarget(mImageReader.getSurface());
            // 自动对焦
            mBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            // 自动曝光
            mBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            // 获取手机方向
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            // 根据设备方向计算设置照片的方向
            mBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
            //拍照
            mCameraDevice.createCaptureSession(Arrays.asList(mSurfaceHolder.getSurface(),mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice) {
                                return;
                            }
                            mCameraCaptureSession = cameraCaptureSession;
                            try {
                                mBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                mPreviewRequest = mBuilder.build();
                                mCameraCaptureSession.capture(mPreviewRequest,null,mCameraHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void startRecordingVideo() {
        if (null == mCameraDevice) {
            return;
        }
        try {
            closePreviewSession();
            initAudioManager();
            mBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();
            Surface previewSurface = mSurfaceHolder.getSurface();
            mBuilder.addTarget(previewSurface);
            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mBuilder.addTarget(recorderSurface);
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    mCameraCaptureSession = cameraCaptureSession;
                    updatePreview();
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mMediaRecorder.start();
                        }
                    });
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Failed", Toast.LENGTH_SHORT).show();
                }
            }, mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    private void closePreviewSession() {
        if (mCameraCaptureSession != null) {
            mCameraCaptureSession.close();
            mCameraCaptureSession = null;
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void stopRecordingVideo() {
        if(mMediaRecorder!=null) {
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            Toast.makeText(this, "Video saved: " + mNextVideoAbsolutePath,
                    Toast.LENGTH_SHORT).show();
            mNextVideoAbsolutePath = null;
            takePreview();
        }
    }
    private String getVideoFilePath(Context context) {
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + System.currentTimeMillis() + ".mp4";
    }

    public void closeCamera(){
        closePreviewSession();
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mMediaRecorder) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }
    /**
     * Update the camera preview. {@link #takePreview()} needs to be called in advance.
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void updatePreview() {
        try {
            mBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            mPreviewRequest= mBuilder.build();
            mCameraCaptureSession.setRepeatingRequest(mPreviewRequest,null, mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
}
