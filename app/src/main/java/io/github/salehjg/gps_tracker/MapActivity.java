package io.github.salehjg.gps_tracker;

import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapActivity extends AppCompatActivity {

    private MapView mapView;
    private Spinner spinnerDay;
    private TextView textPointCount;
    private LocationDao locationDao;
    private ExecutorService executor;

    private final List<Long> dayTimestamps = new ArrayList<>();
    private final List<String> dayLabels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_map);

        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mapView = findViewById(R.id.mapView);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);

        spinnerDay = findViewById(R.id.spinnerDay);
        textPointCount = findViewById(R.id.textPointCount);

        locationDao = LocationDatabase.getInstance(this).locationDao();
        executor = Executors.newSingleThreadExecutor();

        loadDays();
    }

    private void loadDays() {
        executor.execute(() -> {
            List<Long> days = locationDao.getDistinctDays();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd (EEE)", Locale.getDefault());

            dayTimestamps.clear();
            dayLabels.clear();

            for (Long ts : days) {
                dayTimestamps.add(ts);
                dayLabels.add(sdf.format(new Date(ts)));
            }

            runOnUiThread(() -> {
                if (dayLabels.isEmpty()) {
                    dayLabels.add(getString(R.string.no_data));
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                            this, android.R.layout.simple_spinner_item, dayLabels);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerDay.setAdapter(adapter);
                    return;
                }

                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        this, android.R.layout.simple_spinner_item, dayLabels);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerDay.setAdapter(adapter);

                spinnerDay.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                        if (position < dayTimestamps.size()) {
                            loadRoute(dayTimestamps.get(position));
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });
            });
        });
    }

    private void loadRoute(long dayTimestamp) {
        executor.execute(() -> {
            Calendar cal = Calendar.getInstance(TimeZone.getDefault());
            cal.setTimeInMillis(dayTimestamp);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long startOfDay = cal.getTimeInMillis();

            cal.add(Calendar.DAY_OF_MONTH, 1);
            long endOfDay = cal.getTimeInMillis();

            List<LocationEntry> entries = locationDao.getEntriesForDay(startOfDay, endOfDay);

            runOnUiThread(() -> drawRoute(entries));
        });
    }

    private void drawRoute(List<LocationEntry> entries) {
        mapView.getOverlays().clear();

        textPointCount.setText(String.format(Locale.getDefault(),
                getString(R.string.point_count_format), entries.size()));

        if (entries.isEmpty()) {
            mapView.invalidate();
            return;
        }

        List<GeoPoint> points = new ArrayList<>();
        for (LocationEntry e : entries) {
            points.add(new GeoPoint(e.latitude, e.longitude));
        }

        Polyline polyline = new Polyline();
        polyline.setPoints(points);
        polyline.getOutlinePaint().setColor(Color.BLUE);
        polyline.getOutlinePaint().setStrokeWidth(8f);
        mapView.getOverlays().add(polyline);

        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

        Marker startMarker = new Marker(mapView);
        startMarker.setPosition(points.get(0));
        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        startMarker.setTitle("Start: " + timeFmt.format(new Date(entries.get(0).timestamp)));
        mapView.getOverlays().add(startMarker);

        if (points.size() > 1) {
            Marker endMarker = new Marker(mapView);
            endMarker.setPosition(points.get(points.size() - 1));
            endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            endMarker.setTitle("End: " + timeFmt.format(new Date(entries.get(entries.size() - 1).timestamp)));
            mapView.getOverlays().add(endMarker);
        }

        BoundingBox boundingBox = BoundingBox.fromGeoPoints(points);
        mapView.zoomToBoundingBox(boundingBox.increaseByScale(1.3f), true);

        mapView.invalidate();
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
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
