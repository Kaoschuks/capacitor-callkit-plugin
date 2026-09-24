package io.kreador.callkit;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import androidx.annotation.Nullable;
import org.json.JSONObject;

/**
 * Plays the ringtone and vibration for a ringing self-managed call.
 *
 * Telecom does not ring for self-managed calls, and relying on the notification channel's sound
 * is unreliable (channel settings are frozen once created, several OEMs play call-category
 * notification sounds once or not at all). So the plugin rings itself, following the ringer mode:
 * normal → sound + vibration, vibrate → vibration, silent → nothing.
 *
 * Settings: `ringtoneSound` (res/raw name, default: the device ringtone), `vibrate` (default true).
 */
final class CallRinger {

    private static final String TAG = "IonicCallkit";
    private static final long[] VIBRATION_PATTERN = { 0, 1000, 1000 };

    private static MediaPlayer player;
    private static Vibrator vibrator;
    private static String ringingCallId;

    private CallRinger() {}

    static synchronized void start(Context context, String callId) {
        stopInternal();
        ringingCallId = callId;

        JSONObject settings = CallKeepModule.getSettings(context);
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();

        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            playSound(context, settings);
        }
        if (ringerMode != AudioManager.RINGER_MODE_SILENT && settings.optBoolean("vibrate", true)) {
            vibrate(context);
        }
    }

    /** Stops ringing for callId, or whatever is ringing when callId is null. */
    static synchronized void stop(@Nullable String callId) {
        if (callId != null && ringingCallId != null && !callId.equals(ringingCallId)) {
            return;
        }
        stopInternal();
    }

    static synchronized boolean isRinging() {
        return ringingCallId != null;
    }

    private static void stopInternal() {
        ringingCallId = null;
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {}
            player.release();
            player = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

    private static void playSound(Context context, JSONObject settings) {
        for (Uri uri : candidateUris(context, settings)) {
            if (uri == null) {
                continue;
            }
            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                );
                mp.setDataSource(context, uri);
                mp.setLooping(true);
                mp.prepare();
                mp.start();
                player = mp;
                return;
            } catch (Exception e) {
                Log.w(TAG, "[CallRinger] can't play " + uri + ": " + e);
                mp.release();
            }
        }
    }

    private static Uri[] candidateUris(Context context, JSONObject settings) {
        Uri custom = null;
        String sound = settings.optString("ringtoneSound", "");
        if (!sound.isEmpty()) {
            String name = sound.contains(".") ? sound.substring(0, sound.lastIndexOf('.')) : sound;
            int id = context.getResources().getIdentifier(name, "raw", context.getPackageName());
            if (id != 0) {
                custom = Uri.parse("android.resource://" + context.getPackageName() + "/" + id);
            }
        }
        return new Uri[] {
            custom,
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        };
    }

    private static void vibrate(Context context) {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v = manager.getDefaultVibrator();
        } else {
            v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (v == null || !v.hasVibrator()) {
            return;
        }
        VibrationEffect effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Ringtone usage keeps vibrating while the app is in the background.
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE));
        } else {
            v.vibrate(effect, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());
        }
        vibrator = v;
    }
}
