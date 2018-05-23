package com.example.administrator.live;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.alex.livertmppushsdk.FdkAacEncode;
import com.alex.livertmppushsdk.RtmpSessionManager;
import com.alex.livertmppushsdk.SWVideoEncoder;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class LiveActivity extends Activity {
    private final static int ID_RTMP_PUSH_START = 100;
    private final int WIDTH_DEF = 480;
    private final int HEIGHT_DEF = 640;
    private final int FRAMERATE_DEF = 20;
    private final int BITRATE_DEF = 800 * 1000;
    private final int SAMPLE_RATE_DEF = 22050;
    private final int CHANNEL_NUMBER_DEF = 2;
    private final String LOG_TAG = "MainActivity";
    private final boolean DEBUG_ENABLE = false;
    private String mRtmpUrl = "rtmp://192.168.0.100:1935/live/12345678";
    PowerManager.WakeLock mWakeLock;
    private DataOutputStream mOutputStream = null;
    private AudioRecord mAudioRecoder = null;
    private byte[] mRecorderBuffer = null;
    private FdkAacEncode mFdkaacEnc = null;
    private int mFdkaacHandle = 0;
    private SurfaceView mSurfaceView = null;
    private SurfaceHolder mSurfaceHolder = null;
    private CameraManager mCameraManager = null;
    private CameraDevice mCameraDevice = null;
    private CameraCaptureSession mVideoSesstion = null;
    private CaptureRequest.Builder mBuilder = null;
    private CaptureRequest mPreviewRequest = null;
    private boolean mIsFront = false;
    private SWVideoEncoder mSwEncH264 = null;
    private int mRecorderBufferSize = 0;
    private Button mSwitchCameraBtn = null;
    private boolean mStartFlag = false;
    private int mCameraCodecType = ImageFormat.YV12;
    private byte[] mYuvEdit = new byte[WIDTH_DEF * HEIGHT_DEF * 3 / 2];
    private RtmpSessionManager mRtmpSessionMgr = null;
    private Queue<byte[]> mYUVQueue = new LinkedList<byte[]>();
    private Lock mYuvQueueLock = new ReentrantLock();
    private Thread mH264EncoderThread = null;
    private ImageReader mImageReader = null;
    private Runnable mH264Runnable = new Runnable() {
        @Override
        public void run() {
            while (!mH264EncoderThread.interrupted() && mStartFlag) {
                int iSize = mYUVQueue.size();
                if (iSize > 0) {
                    mYuvQueueLock.lock();
                    byte[] yuvData = mYUVQueue.poll();
                    if (iSize > 9) {
                        Log.i(LOG_TAG, "###YUV Queue len=" + mYUVQueue.size() + ", YUV length=" + yuvData.length);
                    }
                    mYuvQueueLock.unlock();
                    if (yuvData == null) {
                        continue;
                    }
                    if (mIsFront) {
                        mYuvEdit = mSwEncH264.YUV420pRotate270(yuvData, HEIGHT_DEF, WIDTH_DEF);
                    } else {
                        mYuvEdit = mSwEncH264.YUV420pRotate90(yuvData, HEIGHT_DEF, WIDTH_DEF);
                    }
                    byte[] h264Data = mSwEncH264.EncoderH264(mYuvEdit);
                    if (h264Data != null) {
                        mRtmpSessionMgr.InsertVideoData(h264Data);
                        if (DEBUG_ENABLE) {
                            try {
                                mOutputStream.write(h264Data);
                            } catch (IOException e1) {
                                e1.printStackTrace();
                            }
                        }
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                mYUVQueue.clear();
            }
        }
    };
    private Runnable mAacEncoderRunnable = new Runnable() {
        @Override
        public void run() {
            DataOutputStream outputStream = null;
            if (DEBUG_ENABLE) {
                File saveDir = Environment.getExternalStorageDirectory();
                String strFilename = saveDir + "/aaa.aac";
                try {
                    outputStream = new DataOutputStream(new FileOutputStream(strFilename));
                } catch (FileNotFoundException e1) {
                    e1.printStackTrace();
                }
            }
            long sleepTime = SAMPLE_RATE_DEF * 16 * 2 / mRecorderBuffer.length;
            while (!mAacEncoderThread.interrupted() && mStartFlag) {
                int iPCMLen = mAudioRecoder.read(mRecorderBuffer, 0, mRecorderBuffer.length);
                if ((iPCMLen != mAudioRecoder.ERROR_BAD_VALUE) && (iPCMLen != 0)) {
                    if (mFdkaacHandle != 0) {
                        byte[] aacBuffer = mFdkaacEnc.FdkAacEncode(mFdkaacHandle, mRecorderBuffer);
                        if (aacBuffer != null) {
                            long lLen = aacBuffer.length;
                            mRtmpSessionMgr.InsertAudioData(aacBuffer);
                            if (DEBUG_ENABLE) {
                                try {
                                    outputStream.write(aacBuffer);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                } else {
                    Log.i(LOG_TAG, "######fail to get PCM data");
                }
                try {
                    Thread.sleep(sleepTime / 10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            Log.i(LOG_TAG, "AAC Encoder Thread ended ......");
        }
    };
    private Thread mAacEncoderThread = null;
    private void processFrame(byte[] YUV){
        Log.d("liangpan","processFrame YUV========="+YUV.length);
        byte[] yuv420 = null;
        if (mCameraCodecType == ImageFormat.YV12) {
            yuv420 = new byte[YUV.length];
            mSwEncH264.swapYV12toI420_Ex(YUV, yuv420, HEIGHT_DEF, WIDTH_DEF);
        } else if (mCameraCodecType == ImageFormat.NV21) {
            yuv420 = mSwEncH264.swapNV21toI420(YUV, HEIGHT_DEF, WIDTH_DEF);
        }
        Log.i("liangpan", "processFrame yuv420========"+yuv420.length);
        if (yuv420 == null) {
            return;
        }
        if (!mStartFlag) {
            return;
        }
        mYuvQueueLock.lock();
        if (mYUVQueue.size() > 1) {
            mYUVQueue.clear();
        }
        Log.i("liangpan", "processFrame mYUVQueue.offer========"+yuv420.length);
        mYUVQueue.offer(yuv420);
        mYuvQueueLock.unlock();
        Log.i("liangpan", "processFrame mYUVQueue.offer========"+mYUVQueue.size());
    }
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void initCamera() throws CameraAccessException {
        HandlerThread handlerThread = new HandlerThread("Camera2");
        handlerThread.start();
        mCameraHandler = new Handler(handlerThread.getLooper());
        mCameraManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics cameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraId);
        List  keys = cameraCharacteristics.getAvailableCaptureRequestKeys();
        Log.d("liangpan","========"+keys);
        mImageReader = ImageReader.newInstance(1080,1920,ImageFormat.YV12,10);
        mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(final ImageReader reader) {
                mCameraHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Image image = reader.acquireNextImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        processFrame(bytes);
                    }
                });
            }
        },mCameraHandler);
        openCamera();
    }
    private final class SurceCallBack implements SurfaceHolder.Callback {
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            try {
                initCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
        }
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {

        }
    }
    private void start() {
        if (DEBUG_ENABLE) {
            File saveDir = Environment.getExternalStorageDirectory();
            String strFilename = saveDir + "/aaa.h264";
            try {
                mOutputStream = new DataOutputStream(new FileOutputStream(strFilename));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
        mRtmpSessionMgr = new RtmpSessionManager();
        mRtmpSessionMgr.Start(mRtmpUrl);
        int iFormat = mCameraCodecType;
        mSwEncH264 = new SWVideoEncoder(WIDTH_DEF, HEIGHT_DEF, FRAMERATE_DEF, BITRATE_DEF);
        mSwEncH264.start(iFormat);
        mStartFlag = true;
        mH264EncoderThread = new Thread(mH264Runnable);
        mH264EncoderThread.setPriority(Thread.MAX_PRIORITY);
        mH264EncoderThread.start();
        mAudioRecoder.startRecording();
        mAacEncoderThread = new Thread(mAacEncoderRunnable);
        mAacEncoderThread.setPriority(Thread.MAX_PRIORITY);
        mAacEncoderThread.start();
    }

    private void stop() {
        mStartFlag = false;

        mAacEncoderThread.interrupt();
        mH264EncoderThread.interrupt();

        mAudioRecoder.stop();
        mSwEncH264.stop();

        mRtmpSessionMgr.Stop();

        mYuvQueueLock.lock();
        mYUVQueue.clear();
        mYuvQueueLock.unlock();

        if (DEBUG_ENABLE) {
            if (mOutputStream != null) {
                try {
                    mOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private View.OnClickListener mSwitchCameraOnClickedEvent = new View.OnClickListener() {
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onClick(View arg0) {
            if(mIsFront) {
                mIsFront = false;
            }else{
                mIsFront = true;
            }
            //closeCamera();
            try {
                openCamera();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    };

    private void initAudioRecord() {
        mRecorderBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_DEF,
                AudioFormat.CHANNEL_OUT_STEREO | AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        mAudioRecoder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_DEF, AudioFormat.CHANNEL_OUT_STEREO | AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, mRecorderBufferSize);
        mRecorderBuffer = new byte[mRecorderBufferSize];
        mFdkaacEnc = new FdkAacEncode();
        mFdkaacHandle = mFdkaacEnc.FdkAacInit(SAMPLE_RATE_DEF, CHANNEL_NUMBER_DEF);
    }

    public Handler mCameraHandler = new Handler() {
        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case ID_RTMP_PUSH_START: {
                    start();
                    break;
                }
            }
        }
    };

    private void rtmpStartMessage() {
        Message msg = new Message();
        msg.what = ID_RTMP_PUSH_START;
        Bundle b = new Bundle();
        b.putInt("ret", 0);
        msg.setData(b);
        mCameraHandler.sendMessage(msg);
    }

    private void initAll() {
        WindowManager wm = this.getWindowManager();
        int width = wm.getDefaultDisplay().getWidth();
        int height = wm.getDefaultDisplay().getHeight();
        int iNewWidth = (int) (height * 3.0 / 4.0);
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        int iPos = width - iNewWidth;
        layoutParams.setMargins(iPos, 0, 0, 0);
        mSurfaceView = this.findViewById(R.id.live_view);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.setKeepScreenOn(true);
        mSurfaceHolder.addCallback(new SurceCallBack());
        mSurfaceView.setLayoutParams(layoutParams);
        initAudioRecord();
        mSwitchCameraBtn = findViewById(R.id.switch_caramer);
        mSwitchCameraBtn.setOnClickListener(mSwitchCameraOnClickedEvent);
        rtmpStartMessage();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        Intent intent = getIntent();
        mRtmpUrl = intent.getStringExtra(MainActivity.LIVE_URL);
        initAll();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "My Tag");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(LOG_TAG, "MainActivity onDestroy...");
    }

    @Override
    protected void onResume() {
        super.onResume();
        mWakeLock.acquire();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mWakeLock.release();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            AlertDialog isExit = new AlertDialog.Builder(this).create();
            isExit.setTitle("系统提示");
            isExit.setMessage("确定要退出吗");
            isExit.setButton("确定", listener);
            isExit.setButton2("取消", listener);
            isExit.show();
        }
        return false;
    }

    /**
     * 监听对话框里面的button点击事件
     */
    DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onClick(DialogInterface dialog, int which) {
            switch (which) {
                case AlertDialog.BUTTON_POSITIVE: {
                    closeCamera();
                    if (mStartFlag) {
                        stop();
                    }
                    LiveActivity.this.finish();
                    break;
                }
                case AlertDialog.BUTTON_NEGATIVE:
                    break;
                default:
                    break;
            }
        }
    };
    private String mCameraId = "" + CameraCharacteristics.LENS_FACING_BACK;
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void openCamera() throws CameraAccessException {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if(mIsFront){
            mCameraId = "" + CameraCharacteristics.LENS_FACING_BACK;
        }else{
            mCameraId = "" + CameraCharacteristics.LENS_FACING_FRONT;
        }
        CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);

        mCameraManager.openCamera(mCameraId, stateCallback, mCameraHandler);
    }
    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback(){
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            takePreview();
        }
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            if(mCameraDevice!=null){
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Toast.makeText(LiveActivity.this, "打开相机失败", Toast.LENGTH_LONG).show();
        }
    };
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void closeCamera(){
        closePreviewSession();
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mAudioRecoder) {
            mAudioRecoder.release();
            mAudioRecoder = null;
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void closePreviewSession(){
        if (mVideoSesstion != null) {
            mVideoSesstion.close();
            mVideoSesstion = null;
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void takePreview(){
        try {
            if (mCameraDevice == null) {
                return;
            }
            closePreviewSession();
            mBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = Arrays.asList(mSurfaceHolder.getSurface(), mImageReader.getSurface());
            mBuilder.addTarget( mSurfaceHolder.getSurface());
            mBuilder.addTarget( mImageReader.getSurface());
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (null == mCameraDevice){
                        return;
                    }
                    Log.d("liangpan","onConfigured=========");
                    mVideoSesstion = session;
                    LiveActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mAudioRecoder.startRecording();
                        }
                    });
                    try {
                        mBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        mPreviewRequest = mBuilder.build();
                        mVideoSesstion.setRepeatingRequest(mPreviewRequest,null, mCameraHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(LiveActivity.this,"配置失败",Toast.LENGTH_LONG).show();
                }
            }, mCameraHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
}
