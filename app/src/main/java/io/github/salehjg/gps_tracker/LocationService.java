package io.github.salehjg.gps_tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocationService extends Service implements LocationListener {

    public static final String EXTRA_INTERVAL_MS = "interval_ms";
    public static final String ACTION_STOP = "io.github.salehjg.gps_tracker.STOP";

    private static final String CHANNEL_ID = "gps_tracker_channel";
    private static final int NOTIFICATION_ID = 1;

    private LocationManager locationManager;
    private LocationDao locationDao;
    private ExecutorService executor;
    private NotificationManager notificationManager;
    private Handler tickHandler;
    private Runnable tickRunnable;
    private long intervalMs = 5000;
    private volatile Location lastLocation;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationDao = LocationDatabase.getInstance(this).locationDao();
        executor = Executors.newSingleThreadExecutor();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        tickHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 5000);
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting GPS tracking..."));

        try {
            locationManager.removeUpdates(this);
            // Seed lastLocation so the tick can record immediately even if
            // onLocationChanged has not fired yet (e.g. phone is stationary).
            Location seed = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (seed == null) {
                seed = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (seed != null && lastLocation == null) {
                lastLocation = seed;
            }
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    0f,
                    this
            );
        } catch (SecurityException e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Start periodic tick to record location even when stationary
        stopTick();
        tickRunnable = new Runnable() {
            @Override
            public void run() {
                recordLastLocation();
                tickHandler.postDelayed(this, intervalMs);
            }
        };
        tickHandler.postDelayed(tickRunnable, intervalMs);

        return START_STICKY;
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        lastLocation = location;
        recordLocation(location);
    }

    private void recordLastLocation() {
        Location loc = lastLocation;
        if (loc != null) {
            // Create a copy with the current time so stationary entries get distinct timestamps
            Location copy = new Location(loc);
            copy.setTime(System.currentTimeMillis());
            recordLocation(copy);
        }
    }

    private void recordLocation(Location location) {
        LocationEntry entry = new LocationEntry(
                location.getLatitude(),
                location.getLongitude(),
                location.getAltitude(),
                location.getAccuracy(),
                location.getSpeed(),
                location.getTime()
        );

        executor.execute(() -> locationDao.insert(entry));

        String text = String.format("Lat: %.6f, Lon: %.6f, Alt: %.1fm",
                location.getLatitude(), location.getLongitude(), location.getAltitude());
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTick();
        locationManager.removeUpdates(this);
        executor.shutdown();
    }

    private void stopTick() {
        if (tickRunnable != null) {
            tickHandler.removeCallbacks(tickRunnable);
            tickRunnable = null;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "GPS Tracking",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows current GPS tracking status");
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String contentText) {
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent mainPending = PendingIntent.getActivity(
                this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, LocationService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Tracker")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(mainPending)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
                .setOngoing(true)
                .build();
    }
}
