package camera.pixel.pulse;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // Views
    private PreviewView viewFinder;
    private ImageButton btnSettings, btnSwitchCamera, btnFlash, btnStop;
    private FrameLayout btnCapture;
    private View shutterInner;
    private TextView tabPhoto, tabVideo, timerText;
    private View recDot;
    private View permissionOverlay;

    // CameraX
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview preview;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private boolean isVideoMode = false;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private boolean torchOn = false;

    private final int[] flashModes = {
            ImageCapture.FLASH_MODE_OFF, ImageCapture.FLASH_MODE_ON, ImageCapture.FLASH_MODE_AUTO
    };
    private int flashIndex = 0;

    private OrientationEventListener orientationEventListener;
    private int currentRotation = Surface.ROTATION_0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<String[]> permissionLauncher;

    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) {
                recDot.setVisibility(View.INVISIBLE);
                return;
            }
            if (isPaused) {
                recDot.setVisibility(View.VISIBLE);
                return;
            }
            recDot.setVisibility(recDot.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
            mainHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupListeners();
        setupOrientationListener();

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
                    boolean allGranted = true;
                    for (Boolean g : grants.values()) {
                        if (g == null || !g) allGranted = false;
                    }
                    if (allGranted) {
                        permissionOverlay.setVisibility(View.GONE);
                        startCamera();
                    } else {
                        permissionOverlay.setVisibility(View.VISIBLE);
                        Toast.makeText(this, R.string.permission_rationale, Toast.LENGTH_LONG).show();
                    }
                });

        lensFacing = PrefsHelper.getLensFacing(this, CameraSelector.LENS_FACING_BACK);

        if (hasAllRequiredPermissions()) {
            permissionOverlay.setVisibility(View.GONE);
            startCamera();
        } else {
            permissionOverlay.setVisibility(View.VISIBLE);
            permissionLauncher.launch(requiredPermissions());
        }
    }

    private void bindViews() {
        viewFinder = findViewById(R.id.viewFinder);
        btnSettings = findViewById(R.id.btnSettings);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        btnFlash = findViewById(R.id.btnFlash);
        btnStop = findViewById(R.id.btnStop);
        btnCapture = findViewById(R.id.btnCapture);
        shutterInner = findViewById(R.id.shutterInner);
        tabPhoto = findViewById(R.id.tabPhoto);
        tabVideo = findViewById(R.id.tabVideo);
        timerText = findViewById(R.id.timerText);
        recDot = findViewById(R.id.recDot);
        permissionOverlay = findViewById(R.id.permissionOverlay);
        findViewById(R.id.btnGrantPermission).setOnClickListener(v ->
                permissionLauncher.launch(requiredPermissions()));
    }

    private void setupListeners() {
        btnSettings.setOnClickListener(v -> {
            if (isRecording) return;
            startActivity(new Intent(this, SettingsActivity.class));
        });

        tabPhoto.setOnClickListener(v -> setMode(false));
        tabVideo.setOnClickListener(v -> setMode(true));

        btnCapture.setOnClickListener(v -> {
            if (!isVideoMode) {
                takePhoto();
            } else if (!isRecording) {
                startRecording();
            } else {
                togglePauseResume();
            }
        });

        btnStop.setOnClickListener(v -> stopRecording());

        btnSwitchCamera.setOnClickListener(v -> {
            if (isRecording) return;
            lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                    ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            PrefsHelper.setLensFacing(this, lensFacing);
            bindCameraUseCases();
        });

        btnFlash.setOnClickListener(v -> {
            if (camera == null) return;
            if (!isVideoMode) {
                flashIndex = (flashIndex + 1) % flashModes.length;
                if (imageCapture != null) imageCapture.setFlashMode(flashModes[flashIndex]);
                updateFlashIcon();
            } else {
                torchOn = !torchOn;
                try {
                    camera.getCameraControl().enableTorch(torchOn);
                } catch (Exception e) {
                    Log.w(TAG, "Torch toggle failed", e);
                }
                updateFlashIcon();
            }
        });
    }

    private void setupOrientationListener() {
        orientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;
                int rotation;
                if (orientation >= 315 || orientation < 45) {
                    rotation = Surface.ROTATION_0;
                } else if (orientation < 135) {
                    rotation = Surface.ROTATION_270;
                } else if (orientation < 225) {
                    rotation = Surface.ROTATION_180;
                } else {
                    rotation = Surface.ROTATION_90;
                }
                if (rotation != currentRotation) {
                    currentRotation = rotation;
                    updateTargetRotation(rotation);
                }
            }
        };
    }

    private void updateTargetRotation(int rotation) {
        if (imageCapture != null) imageCapture.setTargetRotation(rotation);
        if (videoCapture != null) videoCapture.setTargetRotation(rotation);
    }

    // ---------- Permissions ----------

    private String[] requiredPermissions() {
        List<String> list = new ArrayList<>();
        list.add(Manifest.permission.CAMERA);
        list.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return list.toArray(new String[0]);
    }

    private boolean hasAllRequiredPermissions() {
        for (String p : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---------- Camera setup ----------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Failed to get camera provider", e);
                Toast.makeText(this, "Unable to start camera on this device.", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

        CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

        imageCapture = buildImageCapture(true);
        videoCapture = buildVideoCapture(true);

        try {
            camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture);
        } catch (Exception e1) {
            Log.w(TAG, "Full bind failed, retrying with safe defaults", e1);
            try {
                imageCapture = buildImageCapture(false);
                videoCapture = buildVideoCapture(false);
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture);
            } catch (Exception e2) {
                Log.w(TAG, "Bind with defaults failed, dropping video capability", e2);
                try {
                    videoCapture = null;
                    camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
                } catch (Exception e3) {
                    Log.e(TAG, "Camera bind failed entirely", e3);
                    Toast.makeText(this, "Unable to start camera on this device.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }

        updateTargetRotation(currentRotation);
        flashIndex = 0;
        torchOn = false;
        applyFlashUiState();
        updateRecordingUi();
    }

    private ImageCapture buildImageCapture(boolean useUserPrefs) {
        ImageCapture.Builder builder = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY);
        try {
            ResolutionSelector.Builder rsBuilder = new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(new AspectRatioStrategy(
                            AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO));
            if (useUserPrefs) {
                int[] saved = PrefsHelper.getPhotoSize(this);
                if (saved[0] > 0 && saved[1] > 0) {
                    rsBuilder.setResolutionStrategy(new ResolutionStrategy(
                            new Size(saved[0], saved[1]),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER));
                }
            }
            builder.setResolutionSelector(rsBuilder.build());
        } catch (Exception e) {
            Log.w(TAG, "Could not apply resolution selector, using CameraX defaults", e);
        }
        return builder.build();
    }

    private VideoCapture<Recorder> buildVideoCapture(boolean useUserPrefs) {
        try {
            Recorder.Builder rb = new Recorder.Builder();
            Quality target = useUserPrefs ? qualityFromName(PrefsHelper.getVideoQualityName(this)) : null;

            QualitySelector qs = (target != null)
                    ? QualitySelector.from(target, FallbackStrategy.higherQualityOrLowerThan(target))
                    : QualitySelector.fromOrderedList(
                            Arrays.asList(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                            FallbackStrategy.lowerQualityThan(Quality.SD));
            rb.setQualitySelector(qs);

            if (useUserPrefs) {
                rb.setAspectRatio(AspectRatio.RATIO_4_3);
                Size approx = CameraCapabilities.approximateSizeForQuality(target != null ? target : Quality.UHD);
                rb.setTargetVideoEncodingBitRate(CameraCapabilities.bitrateForSize(approx));
                trySetHevc(rb);
            }

            Recorder recorder = rb.build();
            return VideoCapture.withOutput(recorder);
        } catch (Exception e) {
            Log.w(TAG, "Falling back to a default Recorder configuration", e);
            Recorder recorder = new Recorder.Builder().build();
            return VideoCapture.withOutput(recorder);
        }
    }

    /**
     * Prefers H.265/HEVC when the device supports it, via CameraX's video
     * MIME type API. Uses reflection so this still compiles and runs
     * correctly against CameraX builds where that API isn't present -- it
     * simply falls back to the default codec (H.264/AVC) in that case.
     */
    private void trySetHevc(Recorder.Builder rb) {
        if (!CameraCapabilities.isHevcEncoderSupported()) return;
        try {
            Method m = Recorder.Builder.class.getMethod("setVideoMimeType", String.class);
            m.invoke(rb, "video/hevc");
        } catch (Throwable t) {
            Log.d(TAG, "HEVC MIME override not available, default codec will be used", t);
        }
    }

    private static Quality qualityFromName(String name) {
        if (name == null) return null;
        switch (name) {
            case "UHD":
                return Quality.UHD;
            case "FHD":
                return Quality.FHD;
            case "HD":
                return Quality.HD;
            case "SD":
                return Quality.SD;
            default:
                return null;
        }
    }

    private void applyFlashUiState() {
        boolean hasFlash = camera != null && camera.getCameraInfo().hasFlashUnit();
        btnFlash.setEnabled(hasFlash);
        btnFlash.setAlpha(hasFlash ? 1f : 0.35f);
        updateFlashIcon();
    }

    private void updateFlashIcon() {
        if (!isVideoMode) {
            int mode = flashModes[flashIndex];
            if (mode == ImageCapture.FLASH_MODE_ON) {
                btnFlash.setImageResource(R.drawable.ic_flash_on);
            } else if (mode == ImageCapture.FLASH_MODE_AUTO) {
                btnFlash.setImageResource(R.drawable.ic_flash_auto);
            } else {
                btnFlash.setImageResource(R.drawable.ic_flash_off);
            }
        } else {
            btnFlash.setImageResource(torchOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        }
    }

    // ---------- Mode switching ----------

    private void setMode(boolean video) {
        if (isRecording) return;
        isVideoMode = video;
        tabPhoto.setBackground(video ? null : ContextCompat.getDrawable(this, R.drawable.capsule_selected_bg));
        tabVideo.setBackground(video ? ContextCompat.getDrawable(this, R.drawable.capsule_selected_bg) : null);
        updateFlashIcon();
        updateRecordingUi();
    }

    // ---------- Photo capture ----------

    private void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "Photo capture is not available on this device.", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera");
        }
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Toast.makeText(MainActivity.this, "Photo saved", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(@NonNull androidx.camera.core.ImageCaptureException exception) {
                        Log.e(TAG, "Photo capture failed", exception);
                        Toast.makeText(MainActivity.this, "Photo capture failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ---------- Video capture ----------

    private void startRecording() {
        if (videoCapture == null) {
            Toast.makeText(this, "Video recording is not supported on this device/camera.", Toast.LENGTH_LONG).show();
            return;
        }
        String name = FileNamingHelper.nextVideoFileName(this);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Video");
        }
        MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values)
                .build();

        try {
            androidx.camera.video.PendingRecording pending =
                    videoCapture.getOutput().prepareRecording(this, outputOptions);
            if (hasAudioPermission()) {
                pending = pending.withAudioEnabled();
            }
            activeRecording = pending.start(ContextCompat.getMainExecutor(this), this::onVideoRecordEvent);
            isRecording = true;
            isPaused = false;
            updateRecordingUi();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            Toast.makeText(this, "Could not start recording.", Toast.LENGTH_SHORT).show();
        }
    }

    private void togglePauseResume() {
        if (activeRecording == null) return;
        if (isPaused) {
            activeRecording.resume();
        } else {
            activeRecording.pause();
        }
    }

    private void stopRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
    }

    private void onVideoRecordEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Status) {
            long nanos = event.getRecordingStats().getRecordedDurationNanos();
            timerText.setText(formatDuration(nanos));
        } else if (event instanceof VideoRecordEvent.Pause) {
            isPaused = true;
            updateRecordingUi();
        } else if (event instanceof VideoRecordEvent.Resume) {
            isPaused = false;
            updateRecordingUi();
        } else if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
            isRecording = false;
            isPaused = false;
            activeRecording = null;
            timerText.setText("00:00:00");
            if (finalize.hasError()) {
                Log.e(TAG, "Recording finalized with error: " + finalize.getError());
                Toast.makeText(this, "Recording stopped with an error.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show();
            }
            updateRecordingUi();
        }
    }

    private static String formatDuration(long nanos) {
        long totalSeconds = TimeUnit.NANOSECONDS.toSeconds(nanos);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private void updateRecordingUi() {
        btnStop.setVisibility(isRecording ? View.VISIBLE : View.GONE);
        tabPhoto.setEnabled(!isRecording);
        tabVideo.setEnabled(!isRecording);
        btnSwitchCamera.setEnabled(!isRecording);

        if (isVideoMode) {
            shutterInner.setBackgroundResource(isRecording
                    ? R.drawable.shutter_button_inner_recording
                    : R.drawable.shutter_button_inner_photo);
        } else {
            shutterInner.setBackgroundResource(R.drawable.shutter_button_inner_photo);
        }

        if (isRecording) {
            mainHandler.removeCallbacks(blinkRunnable);
            recDot.setVisibility(View.VISIBLE);
            mainHandler.postDelayed(blinkRunnable, 500);
        } else {
            mainHandler.removeCallbacks(blinkRunnable);
            recDot.setVisibility(View.INVISIBLE);
        }
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
        if (cameraProvider != null && !isRecording) {
            // Re-apply in case resolution/quality settings changed while away.
            bindCameraUseCases();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
    }
          }
