package com.rin.klass;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/** Фоновый режим: пока служба работает, приложение не засыпает и в шторке видно, включён ли «Ловец». */
public class KlassService extends Service {
    private static final String CHANNEL = "klass_bg";
    private static final int ID = 7;
    static final String ACTION_TOGGLE = "com.rin.klass.TOGGLE";

    static void start(Context c) {
        try {
            ContextCompat.startForegroundService(c, new Intent(c, KlassService.class));
        } catch (RuntimeException ignored) {
            // система не разрешила запуск службы сейчас - обойдёмся без неё
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, KlassService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            Prefs.setCatcherOn(this, !Prefs.catcherOn(this));
        }
        show();
        return START_NOT_STICKY;
    }

    private void show() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Работа в фоне", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        boolean on = Prefs.catcherOn(this);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent toggle = new Intent(this, KlassService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getService(this, 1, toggle,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_gift)
                .setContentTitle("Rin-klass " + (on ? "включён" : "выключен"))
                .setContentText(on ? "Подарки на сайте обводятся пунктиром" : "Ловец подарков не работает")
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, on ? "Выключить" : "Включить", togglePi)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID, n);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
