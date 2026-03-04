package io.github.salehjg.gps_tracker;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "location_entries")
public class LocationEntry {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public double latitude;
    public double longitude;
    public double altitude;
    public float accuracy;
    public float speed;
    public long timestamp;

    public LocationEntry(double latitude, double longitude, double altitude,
                         float accuracy, float speed, long timestamp) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.accuracy = accuracy;
        this.speed = speed;
        this.timestamp = timestamp;
    }
}
