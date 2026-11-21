package despia.ext.mediapicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps the process in the foreground while a transcode runs, with the stock progress notification,
 * so leaving the app does not stop a long video. The transcode itself runs in the extension (Media3
 * Transformer on the main looper); this service only holds the foreground state and shows progress.
 *
 * <p>Type: mediaProcessing on Android 15 and later (API 35, the type made for transcoding); before
 * that the platform has no such type and dataSync is used, whose documented uses include local file
 * processing (developer.android.com/develop/background-work/services/fgs/service-types, read
 * 2026-11-20). Android 15 limits mediaProcessing to 6 hours a day; {@link #onTimeout} then stops the
 * transcode (reason background_expired) instead of letting the system crash the app.
 */
public final class TranscodeService extends Service {
    static final String CHANNEL = "nx_mediapicker";
    static final int NOTIFICATION = 0x6d70;

    static void begin(Context c, int percent) {
        Intent i = new Intent(c, TranscodeService.class).putExtra("percent", percent);
        try {
            ContextCompat.startForegroundService(c, i);
        } catch (RuntimeException notAllowed) {
            // Started from the background (ForegroundServiceStartNotAllowedException): the transcode
            // still runs while the process lives; the platform decides how long.
        }
    }

    static void end(Context c) {
        c.stopService(new Intent(c, TranscodeService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int percent = intent == null ? 0 : intent.getIntExtra("percent", 0);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.nx_mediapicker_channel),
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.nx_mediapicker_transcoding))
                .setProgress(100, percent, percent <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
        int type = 0;
        if (Build.VERSION.SDK_INT >= 35) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
        } else if (Build.VERSION.SDK_INT >= 29) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, n, type);
        } catch (RuntimeException notAllowed) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        MediaPickerExtension.transcodeTimedOut();
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
