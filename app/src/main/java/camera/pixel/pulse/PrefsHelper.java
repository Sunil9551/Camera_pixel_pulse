package camera.pixel.pulse;

import android.content.Context;
import android.content.SharedPreferences;

/** Small wrapper around SharedPreferences for the few user-chosen settings. */
public final class PrefsHelper {

    private static final String PREFS_NAME = "camera_pixel_pulse_prefs";
    private static final String KEY_PHOTO_W = "photo_w";
    private static final String KEY_PHOTO_H = "photo_h";
    private static final String KEY_VIDEO_QUALITY = "video_quality";
    private static final String KEY_LENS_FACING = "lens_facing";

    private PrefsHelper() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void setPhotoSize(Context c, int w, int h) {
        prefs(c).edit().putInt(KEY_PHOTO_W, w).putInt(KEY_PHOTO_H, h).apply();
    }

    /** Returns {width, height}; {0, 0} means "not set / use highest available". */
    public static int[] getPhotoSize(Context c) {
        SharedPreferences p = prefs(c);
        return new int[]{p.getInt(KEY_PHOTO_W, 0), p.getInt(KEY_PHOTO_H, 0)};
    }

    public static void setVideoQualityName(Context c, String name) {
        prefs(c).edit().putString(KEY_VIDEO_QUALITY, name).apply();
    }

    /** Returns a Quality name ("UHD"/"FHD"/"HD"/"SD") or null if not set. */
    public static String getVideoQualityName(Context c) {
        return prefs(c).getString(KEY_VIDEO_QUALITY, null);
    }

    public static void setLensFacing(Context c, int facing) {
        prefs(c).edit().putInt(KEY_LENS_FACING, facing).apply();
    }

    public static int getLensFacing(Context c, int defaultFacing) {
        return prefs(c).getInt(KEY_LENS_FACING, defaultFacing);
    }
}
