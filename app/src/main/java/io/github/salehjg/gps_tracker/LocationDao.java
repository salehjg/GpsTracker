package io.github.salehjg.gps_tracker;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface LocationDao {

    @Insert
    void insert(LocationEntry entry);

    @Query("SELECT * FROM location_entries WHERE timestamp >= :startOfDay AND timestamp < :endOfDay ORDER BY timestamp ASC")
    List<LocationEntry> getEntriesForDay(long startOfDay, long endOfDay);

    @Query("SELECT MIN(timestamp) as timestamp FROM location_entries GROUP BY (timestamp / 86400000) ORDER BY timestamp DESC")
    List<Long> getDistinctDays();

    @Query("SELECT COUNT(*) FROM location_entries")
    int getTotalCount();

    @Query("SELECT * FROM location_entries WHERE timestamp >= :startTime AND timestamp < :endTime ORDER BY timestamp ASC")
    List<LocationEntry> getEntriesInRange(long startTime, long endTime);

    @Query("DELETE FROM location_entries")
    void deleteAll();
}
