package io.github.salehjg.gps_tracker;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "gps_tracker_prefs";
    private static final String KEY_INTERVAL_INDEX = "interval_index";
    private static final String KEY_TRACKING = "is_tracking";

    private final String[] intervalLabels = {
            "1 second", "5 seconds", "15 seconds", "30 seconds",
            "1 minute", "5 minutes", "10 minutes", "30 minutes"
    };
    private final long[] intervalValues = {
            1000, 5000, 15000, 30000,
            60000, 300000, 600000, 1800000
    };

    private Spinner spinnerInterval;
    private MaterialButton buttonStartStop;
    private TextView textStatus;
    private SharedPreferences prefs;
    private boolean isTracking = false;

    private LocationDao locationDao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> foregroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean fine = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                Boolean coarse = result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false);
                if (Boolean.TRUE.equals(fine) && Boolean.TRUE.equals(coarse)) {
                    requestBackgroundLocation();
                }
            });

    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    requestNotificationPermission();
                }
            });

    private final ActivityResultLauncher<String> notificationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                startTrackingService();
            });

    private final ActivityResultLauncher<String[]> importDbLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    confirmAndImportDatabase(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setSupportActionBar(findViewById(R.id.toolbar));

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        spinnerInterval = findViewById(R.id.spinnerInterval);
        buttonStartStop = findViewById(R.id.buttonStartStop);
        textStatus = findViewById(R.id.textStatus);

        locationDao = LocationDatabase.getInstance(this).locationDao();

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, intervalLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerInterval.setAdapter(adapter);
        spinnerInterval.setSelection(prefs.getInt(KEY_INTERVAL_INDEX, 1));

        isTracking = isServiceRunning();
        updateUI();

        buttonStartStop.setOnClickListener(v -> {
            if (isTracking) {
                stopTrackingService();
            } else {
                beginPermissionFlow();
            }
        });

        findViewById(R.id.buttonViewMap).setOnClickListener(v ->
                startActivity(new Intent(this, MapActivity.class)));

        findViewById(R.id.buttonDestinations).setOnClickListener(v ->
                startActivity(new Intent(this, DestinationsActivity.class)));

        findViewById(R.id.buttonCommutes).setOnClickListener(v ->
                startActivity(new Intent(this, CommutesActivity.class)));

        findViewById(R.id.buttonExportDb).setOnClickListener(v -> exportDatabase());

        findViewById(R.id.buttonImportDb).setOnClickListener(v ->
                importDbLauncher.launch(new String[]{"*/*"}));

        findViewById(R.id.buttonWipeDb).setOnClickListener(v -> wipeDatabase());
    }

    @Override
    protected void onResume() {
        super.onResume();
        isTracking = isServiceRunning();
        updateUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void exportDatabase() {
        executor.execute(() -> {
            int count = locationDao.getTotalCount();
            if (count == 0) {
                runOnUiThread(() -> Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show());
                return;
            }

            // Flush WAL to main database file before copying
            LocationDatabase.getInstance(this).getOpenHelper()
                    .getWritableDatabase().execSQL("PRAGMA wal_checkpoint(FULL)");

            File dbFile = getDatabasePath(LocationDatabase.DB_NAME);
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String filename = "gps_tracker_export_" + timestamp + ".db";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/x-sqlite3");
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download");

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                runOnUiThread(() -> Toast.makeText(this, R.string.export_failure, Toast.LENGTH_SHORT).show());
                return;
            }

            try (InputStream is = new FileInputStream(dbFile);
                 OutputStream os = getContentResolver().openOutputStream(uri)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.export_success, filename), Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.export_failure, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void wipeDatabase() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.wipe_confirm_title)
                .setMessage(R.string.wipe_confirm_message)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        executor.execute(() -> {
                            locationDao.deleteAll();
                            runOnUiThread(() -> Toast.makeText(this,
                                    R.string.wipe_success, Toast.LENGTH_SHORT).show());
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmAndImportDatabase(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.import_confirm_title)
                .setMessage(R.string.import_confirm_message)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        executor.execute(() -> {
                            LocationDatabase.closeDatabase();

                            File dbFile = getDatabasePath(LocationDatabase.DB_NAME);
                            File walFile = new File(dbFile.getPath() + "-wal");
                            File shmFile = new File(dbFile.getPath() + "-shm");

                            try (InputStream is = getContentResolver().openInputStream(uri);
                                 OutputStream os = new FileOutputStream(dbFile)) {
                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                while ((bytesRead = is.read(buffer)) != -1) {
                                    os.write(buffer, 0, bytesRead);
                                }
                                walFile.delete();
                                shmFile.delete();

                                locationDao = LocationDatabase.getInstance(this).locationDao();
                                runOnUiThread(() -> Toast.makeText(this,
                                        R.string.import_success, Toast.LENGTH_LONG).show());
                            } catch (Exception e) {
                                locationDao = LocationDatabase.getInstance(this).locationDao();
                                runOnUiThread(() -> Toast.makeText(this,
                                        R.string.import_failure, Toast.LENGTH_SHORT).show());
                            }
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void beginPermissionFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            foregroundLocationLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        } else {
            requestBackgroundLocation();
        }
    }

    private void requestBackgroundLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setTitle("Background Location")
                    .setMessage("GPS Tracker needs \"Allow all the time\" location access to continue tracking when the app is closed. On the next screen, please select \"Allow all the time\".")
                    .setPositiveButton("Continue", (d, w) ->
                            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            requestNotificationPermission();
        }
    }

    private void requestNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            startTrackingService();
        }
    }

    private void startTrackingService() {
        int selectedIndex = spinnerInterval.getSelectedItemPosition();
        prefs.edit()
                .putInt(KEY_INTERVAL_INDEX, selectedIndex)
                .putBoolean(KEY_TRACKING, true)
                .apply();

        Intent intent = new Intent(this, LocationService.class);
        intent.putExtra(LocationService.EXTRA_INTERVAL_MS, intervalValues[selectedIndex]);
        startForegroundService(intent);

        isTracking = true;
        updateUI();
    }

    private void stopTrackingService() {
        stopService(new Intent(this, LocationService.class));
        prefs.edit().putBoolean(KEY_TRACKING, false).apply();
        isTracking = false;
        updateUI();
    }

    private void updateUI() {
        if (isTracking) {
            textStatus.setText(R.string.status_tracking);
            buttonStartStop.setText(R.string.stop_tracking);
            spinnerInterval.setEnabled(false);
        } else {
            textStatus.setText(R.string.status_idle);
            buttonStartStop.setText(R.string.start_tracking);
            spinnerInterval.setEnabled(true);
        }
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (LocationService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
