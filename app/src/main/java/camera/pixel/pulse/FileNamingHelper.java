package camera.pixel.pulse;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds sequential Video_0000.mp4, Video_0001.mp4... names in DCIM/Video. */
public final class FileNamingHelper {

    private static final String TAG = "FileNamingHelper";
    private static final Pattern VIDEO_NAME_PATTERN = Pattern.compile("Video_(\\d{4,})\\.mp4");

    private FileNamingHelper() {
    }

    public static String nextVideoFileName(Context context) {
        int next = 0;
        try {
            ContentResolver resolver = context.getContentResolver();
            String[] projection = {MediaStore.Video.Media.DISPLAY_NAME};
            String selection = MediaStore.Video.Media.DISPLAY_NAME + " LIKE ?";
            String[] args = {"Video\\_%.mp4"};
            try (Cursor cursor = resolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, args, null)) {
                if (cursor != null) {
                    int nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                    while (cursor.moveToNext()) {
                        String name = nameCol >= 0 ? cursor.getString(nameCol) : null;
                        if (name == null) continue;
                        Matcher m = VIDEO_NAME_PATTERN.matcher(name);
                        if (m.matches()) {
                            try {
                                int n = Integer.parseInt(m.group(1));
                                if (n + 1 > next) next = n + 1;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not query existing video names, defaulting to 0000", e);
        }
        return String.format(Locale.US, "Video_%04d.mp4", next);
    }
}
