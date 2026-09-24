package camera.pixel.pulse;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.util.Size;

import androidx.camera.core.CameraInfo;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Helper for enumerating hardware-supported photo/video capabilities so
 * nothing in the app is hardcoded to a specific device.
 */
public final class CameraCapabilities {

    private static final String TAG = "CameraCapabilities";
    private static final double TARGET_RATIO = 4.0 / 3.0;
    private static final double RATIO_TOLERANCE = 0.02;

    private CameraCapabilities() {
    }

    /**
     * Returns JPEG output sizes supported by the given camera that match a
     * 4:3 (or 3:4) aspect ratio, sorted largest first. Never throws; returns
     * an empty list on any failure so callers can fall back gracefully.
     */
    public static List<Size> getPhoto4x3Sizes(Context context, String cameraId) {
        List<Size> result = new ArrayList<>();
        if (cameraId == null) return result;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return result;
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return result;
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null) return result;
            for (Size s : sizes) {
                if (isCloseToRatio(s.getWidth(), s.getHeight(), TARGET_RATIO)) {
                    result.add(s);
                }
            }
            result.sort(new Comparator<Size>() {
                @Override
                public int compare(Size a, Size b) {
                    long areaA = (long) a.getWidth() * a.getHeight();
                    long areaB = (long) b.getWidth() * b.getHeight();
                    return Long.compare(areaB, areaA);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Failed to enumerate photo sizes", e);
        }
        return result;
    }

    private static boolean isCloseToRatio(int w, int h, double targetRatio) {
        if (w <= 0 || h <= 0) return false;
        double ratio = Math.max(w, h) / (double) Math.min(w, h);
        return Math.abs(ratio - targetRatio) < RATIO_TOLERANCE;
    }

    /**
     * Returns the device/camera-supported CameraX video Qualities, ordered
     * highest-first (UHD, FHD, HD, SD subset — whichever the hardware and
     * codec actually support). Never throws.
     */
    public static List<Quality> getSupportedVideoQualities(CameraInfo cameraInfo) {
        List<Quality> result = new ArrayList<>();
        try {
            if (cameraInfo != null) {
                result.addAll(QualitySelector.getSupportedQualities(cameraInfo));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query supported video qualities", e);
        }
        return result;
    }

    /**
     * Approximate width x height for a given CameraX Quality when captured
     * at 4:3, used only for display and bitrate math. CameraX itself picks
     * the exact final size at bind time.
     */
    public static Size approximateSizeForQuality(Quality quality) {
        if (quality == Quality.UHD) return new Size(2880, 2160);
        if (quality == Quality.FHD) return new Size(1440, 1080);
        if (quality == Quality.HD) return new Size(960, 720);
        if (quality == Quality.SD) return new Size(640, 480);
        return new Size(1440, 1080);
    }

    /**
     * Target video bitrate in bits/sec, scaled proportionally from the
     * requirement of 24 Mbps at 2880x2160 (2160p, 4:3).
     */
    public static int bitrateForSize(Size size) {
        final long baseArea = 2880L * 2160L;
        final int baseBitrate = 24_000_000;
        long area = (long) size.getWidth() * size.getHeight();
        long bitrate = (long) (baseBitrate * (area / (double) baseArea));
        // Clamp to a sane floor so very small resolutions still look decent.
        if (bitrate < 2_000_000) bitrate = 2_000_000;
        if (bitrate > baseBitrate) bitrate = baseBitrate;
        return (int) bitrate;
    }

    /** Whether this device has a usable hardware/software HEVC encoder. */
    public static boolean isHevcEncoderSupported() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query codec list", e);
        }
        return false;
    }
  }
