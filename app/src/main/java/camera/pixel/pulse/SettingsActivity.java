package camera.pixel.pulse;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the user pick a photo resolution and video quality from lists built
 * dynamically from this device's actual hardware capabilities — nothing is
 * hardcoded, so the list is empty-safe and always reflects what the phone
 * can really do.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    private Spinner spinnerPhoto;
    private Spinner spinnerVideo;
    private TextView textCodecInfo;

    private final List<Size> photoSizes = new ArrayList<>();
    private final List<androidx.camera.video.Quality> videoQualities = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        spinnerPhoto = findViewById(R.id.spinnerPhotoResolution);
        spinnerVideo = findViewById(R.id.spinnerVideoResolution);
        textCodecInfo = findViewById(R.id.textCodecInfo);
        Button save = findViewById(R.id.btnSaveSettings);

        boolean hevc = CameraCapabilities.isHevcEncoderSupported();
        textCodecInfo.setText(hevc
                ? "Video codec: H.265 (HEVC) — automatically used when possible"
                : "Video codec: H.264 (AVC) — this device does not support H.265");

        populatePhotoSizes();
        populateVideoQualities();

        save.setOnClickListener(v -> {
            saveSelections();
            Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private String findBackCameraId() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager == null) return null;
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
            // Fall back to first available camera if no back camera found.
            String[] ids = manager.getCameraIdList();
            return ids.length > 0 ? ids[0] : null;
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve back camera id", e);
            return null;
        }
    }

    private void populatePhotoSizes() {
        String camId = findBackCameraId();
        photoSizes.clear();
        photoSizes.addAll(CameraCapabilities.getPhoto4x3Sizes(this, camId));

        List<String> labels = new ArrayList<>();
        labels.add("Auto (Highest available)");
        for (Size s : photoSizes) {
            labels.add(s.getWidth() + " x " + s.getHeight());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        spinnerPhoto.setAdapter(adapter);

        int[] saved = PrefsHelper.getPhotoSize(this);
        if (saved[0] > 0) {
            for (int i = 0; i < photoSizes.size(); i++) {
                Size s = photoSizes.get(i);
                if (s.getWidth() == saved[0] && s.getHeight() == saved[1]) {
                    spinnerPhoto.setSelection(i + 1);
                    break;
                }
            }
        } else {
            spinnerPhoto.setSelection(0);
        }
    }

    private void populateVideoQualities() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                CameraInfo backInfo = null;
                try {
                    CameraSelector backSelector = new CameraSelector.Builder()
                            .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
                    List<CameraInfo> filtered = backSelector.filter(provider.getAvailableCameraInfos());
                    if (!filtered.isEmpty()) backInfo = filtered.get(0);
                } catch (Exception ignored) {
                }
                if (backInfo == null && !provider.getAvailableCameraInfos().isEmpty()) {
                    backInfo = provider.getAvailableCameraInfos().get(0);
                }

                videoQualities.clear();
                videoQualities.addAll(CameraCapabilities.getSupportedVideoQualities(backInfo));
                bindVideoSpinner();
            } catch (Exception e) {
                Log.w(TAG, "Could not enumerate video qualities", e);
                bindVideoSpinner();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindVideoSpinner() {
        List<String> labels = new ArrayList<>();
        labels.add("Auto (Highest available)");
        for (androidx.camera.video.Quality q : videoQualities) {
            Size approx = CameraCapabilities.approximateSizeForQuality(q);
            int mbps = CameraCapabilities.bitrateForSize(approx) / 1_000_000;
            labels.add(qualityLabel(q) + "  (~" + approx.getWidth() + "x" + approx.getHeight()
                    + ", " + mbps + " Mbps)");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        spinnerVideo.setAdapter(adapter);

        String savedQuality = PrefsHelper.getVideoQualityName(this);
        if (savedQuality != null) {
            for (int i = 0; i < videoQualities.size(); i++) {
                if (simpleName(videoQualities.get(i)).equals(savedQuality)) {
                    spinnerVideo.setSelection(i + 1);
                    return;
                }
            }
        }
        spinnerVideo.setSelection(0);
    }

    private static String qualityLabel(androidx.camera.video.Quality q) {
        if (q == androidx.camera.video.Quality.UHD) return "UHD (2160p)";
        if (q == androidx.camera.video.Quality.FHD) return "FHD (1080p)";
        if (q == androidx.camera.video.Quality.HD) return "HD (720p)";
        if (q == androidx.camera.video.Quality.SD) return "SD (480p)";
        return "Auto";
    }

    /** Stable key used only for persistence/matching (not shown to the user). */
    static String simpleName(androidx.camera.video.Quality q) {
        if (q == androidx.camera.video.Quality.UHD) return "UHD";
        if (q == androidx.camera.video.Quality.FHD) return "FHD";
        if (q == androidx.camera.video.Quality.HD) return "HD";
        if (q == androidx.camera.video.Quality.SD) return "SD";
        return "AUTO";
    }

    private void saveSelections() {
        int photoPos = spinnerPhoto.getSelectedItemPosition();
        if (photoPos <= 0 || photoPos - 1 >= photoSizes.size()) {
            PrefsHelper.setPhotoSize(this, 0, 0);
        } else {
            Size s = photoSizes.get(photoPos - 1);
            PrefsHelper.setPhotoSize(this, s.getWidth(), s.getHeight());
        }

        int videoPos = spinnerVideo.getSelectedItemPosition();
        if (videoPos <= 0 || videoPos - 1 >= videoQualities.size()) {
            PrefsHelper.setVideoQualityName(this, null);
        } else {
            PrefsHelper.setVideoQualityName(this, simpleName(videoQualities.get(videoPos - 1)));
        }
    }
  }
