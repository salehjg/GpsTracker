package io.github.salehjg.gps_tracker;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Pair;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CommutesActivity extends AppCompatActivity {

    private MaterialButton buttonDateRange;
    private EditText editRadius;
    private EditText editTimeThreshold;
    private ProgressBar progressBar;
    private TextView textMessage;
    private TableLayout tableResults;
    private LocationDao locationDao;
    private ExecutorService executor;

    private long rangeStartMs;
    private long rangeEndMs;

    private final SimpleDateFormat dateTimeFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_commutes);

        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        buttonDateRange = findViewById(R.id.buttonDateRange);
        editRadius = findViewById(R.id.editRadius);
        editTimeThreshold = findViewById(R.id.editTimeThreshold);
        progressBar = findViewById(R.id.progressBar);
        textMessage = findViewById(R.id.textMessage);
        tableResults = findViewById(R.id.tableResults);

        locationDao = LocationDatabase.getInstance(this).locationDao();
        executor = Executors.newSingleThreadExecutor();

        // Default date range to today
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        rangeStartMs = cal.getTimeInMillis();
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        rangeEndMs = cal.getTimeInMillis();
        buttonDateRange.setText(dateFmt.format(new Date(rangeStartMs))
                + " — " + dateFmt.format(new Date(rangeEndMs)));

        buttonDateRange.setOnClickListener(v -> showDateRangePicker());
        findViewById(R.id.buttonAnalyze).setOnClickListener(v -> runAnalysis());
    }

    private void showDateRangePicker() {
        MaterialDatePicker<Pair<Long, Long>> picker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText(getString(R.string.select_date_range))
                .build();

        picker.addOnPositiveButtonClickListener(selection -> {
            Long startDateUtc = selection.first;
            Long endDateUtc = selection.second;
            if (startDateUtc == null || endDateUtc == null) return;

            // Extract date components in UTC (picker returns UTC midnight timestamps)
            Calendar utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            Calendar localCal = Calendar.getInstance(TimeZone.getDefault());

            utcCal.setTimeInMillis(startDateUtc);
            localCal.set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH),
                    utcCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
            localCal.set(Calendar.MILLISECOND, 0);
            rangeStartMs = localCal.getTimeInMillis();

            utcCal.setTimeInMillis(endDateUtc);
            localCal.set(utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH),
                    utcCal.get(Calendar.DAY_OF_MONTH), 23, 59, 59);
            localCal.set(Calendar.MILLISECOND, 999);
            rangeEndMs = localCal.getTimeInMillis();

            buttonDateRange.setText(dateFmt.format(new Date(rangeStartMs))
                    + " — " + dateFmt.format(new Date(rangeEndMs)));
        });

        picker.show(getSupportFragmentManager(), "date_range_picker");
    }

    private void runAnalysis() {
        String radiusStr = editRadius.getText().toString().trim();
        double radiusMeters = 100;
        if (!radiusStr.isEmpty()) {
            try {
                radiusMeters = Double.parseDouble(radiusStr);
            } catch (NumberFormatException ignored) {
            }
        }

        String timeStr = editTimeThreshold.getText().toString().trim();
        long minDurationMs = 150_000; // 150 seconds default
        if (!timeStr.isEmpty()) {
            try {
                minDurationMs = (long) (Double.parseDouble(timeStr) * 1000);
            } catch (NumberFormatException ignored) {
            }
        }

        progressBar.setVisibility(View.VISIBLE);
        textMessage.setVisibility(View.GONE);
        clearTableRows();

        final double radius = radiusMeters;
        final long duration = minDurationMs;

        executor.execute(() -> {
            List<LocationEntry> entries = locationDao.getEntriesInRange(rangeStartMs, rangeEndMs);
            List<TripAnalyzer.Destination> destinations =
                    TripAnalyzer.findDestinations(entries, radius, duration);

            // Reverse geocode destinations (needed for commute from/to addresses)
            for (TripAnalyzer.Destination dest : destinations) {
                dest.address = TripAnalyzer.reverseGeocode(this, dest.latitude, dest.longitude);
            }

            List<TripAnalyzer.Commute> commutes =
                    TripAnalyzer.findCommutes(entries, destinations);

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (commutes.isEmpty()) {
                    showMessage(getString(R.string.no_commutes_found));
                } else {
                    textMessage.setVisibility(View.GONE);
                    populateTable(commutes);
                }
            });
        });
    }

    private void populateTable(List<TripAnalyzer.Commute> commutes) {
        for (int i = 0; i < commutes.size(); i++) {
            TripAnalyzer.Commute c = commutes.get(i);
            TableRow row = new TableRow(this);
            if (i % 2 == 0) {
                row.setBackgroundColor(0xFFF5F5F5);
            }

            row.addView(makeCell(String.valueOf(i + 1)));
            row.addView(makeCell(formatAddress(c.startAddress, c.startLat, c.startLon)));
            row.addView(makeCell(formatAddress(c.endAddress, c.endLat, c.endLon)));
            row.addView(makeCell(dateTimeFmt.format(new Date(c.startTime))));
            row.addView(makeCell(dateTimeFmt.format(new Date(c.endTime))));
            row.addView(makeCell(formatDuration(c.durationMs)));
            row.addView(makeCell(formatDistance(c.distanceMeters)));
            row.addView(makeCell(String.format(Locale.US, "+%.0fm", c.elevationGainMeters)));
            row.addView(makeCell(String.format(Locale.US, "-%.0fm", c.elevationLossMeters)));

            tableResults.addView(row);
        }
    }

    private String formatAddress(String address, double lat, double lon) {
        if (address != null && !address.equals("N/A")) {
            return address;
        }
        return String.format(Locale.US, "%.5f, %.5f", lat, lon);
    }

    private TextView makeCell(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(16, 8, 16, 8);
        tv.setTextSize(13);
        tv.setMaxWidth(400);
        return tv;
    }

    private String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        if (hours > 0) {
            return String.format(Locale.US, "%dh %dm", hours, minutes);
        }
        return String.format(Locale.US, "%dm", minutes);
    }

    private String formatDistance(double meters) {
        if (meters >= 1000) {
            return String.format(Locale.US, "%.1f km", meters / 1000.0);
        }
        return String.format(Locale.US, "%.0f m", meters);
    }

    private void clearTableRows() {
        int count = tableResults.getChildCount();
        if (count > 1) {
            tableResults.removeViews(1, count - 1);
        }
    }

    private void showMessage(String msg) {
        textMessage.setText(msg);
        textMessage.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
