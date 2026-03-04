package io.github.salehjg.gps_tracker;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class TripAnalyzer {

    public static class Destination {
        public double latitude;
        public double longitude;
        public double avgAltitude;
        public long arrivalTime;
        public long departureTime;
        public long durationMs;
        public String address;
        public int pointCount;

        public int startIndex;
        public int endIndex;
    }

    public static class Commute {
        public double startLat;
        public double startLon;
        public double startAltitude;
        public double endLat;
        public double endLon;
        public double endAltitude;
        public long startTime;
        public long endTime;
        public long durationMs;
        public double distanceMeters;
        public double elevationGainMeters;
        public double elevationLossMeters;
        public String startAddress;
        public String endAddress;
        public int pointCount;
    }

    /**
     * Compute an adaptive radius from the data: 3x the median consecutive-point distance.
     * Falls back to 100m if there are fewer than 2 points.
     */
    public static double adaptiveRadius(List<LocationEntry> entries) {
        if (entries == null || entries.size() < 2) return 100.0;
        List<Float> distances = new ArrayList<>();
        float[] buf = new float[1];
        for (int i = 1; i < entries.size(); i++) {
            Location.distanceBetween(
                    entries.get(i - 1).latitude, entries.get(i - 1).longitude,
                    entries.get(i).latitude, entries.get(i).longitude, buf);
            distances.add(buf[0]);
        }
        Collections.sort(distances);
        double median = distances.get(distances.size() / 2);
        return Math.max(median * 3.0, 10.0); // floor at 10m
    }

    /**
     * Compute an adaptive time threshold from the data: 3x the median consecutive-point time gap.
     * Falls back to 150 000ms if there are fewer than 2 points.
     */
    public static long adaptiveMinDuration(List<LocationEntry> entries) {
        if (entries == null || entries.size() < 2) return 150_000L;
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < entries.size(); i++) {
            gaps.add(entries.get(i).timestamp - entries.get(i - 1).timestamp);
        }
        Collections.sort(gaps);
        long median = gaps.get(gaps.size() / 2);
        return Math.max(median * 3, 30_000L); // floor at 30s
    }

    public static List<Destination> findDestinations(List<LocationEntry> entries,
                                                      double radiusMeters,
                                                      long minDurationMs) {
        List<Destination> destinations = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return destinations;
        }

        // Adaptive values when 0 is passed
        if (radiusMeters <= 0) {
            radiusMeters = adaptiveRadius(entries);
        }
        if (minDurationMs <= 0) {
            minDurationMs = adaptiveMinDuration(entries);
        }

        int clusterStart = 0;
        double centroidLat = entries.get(0).latitude;
        double centroidLon = entries.get(0).longitude;
        int clusterSize = 1;

        float[] results = new float[1];

        for (int i = 1; i < entries.size(); i++) {
            Location.distanceBetween(
                    centroidLat, centroidLon,
                    entries.get(i).latitude, entries.get(i).longitude,
                    results);

            if (results[0] > radiusMeters) {
                long duration = entries.get(i - 1).timestamp - entries.get(clusterStart).timestamp;
                if (duration >= minDurationMs) {
                    destinations.add(buildDestination(entries, clusterStart, i - 1));
                }
                clusterStart = i;
                centroidLat = entries.get(i).latitude;
                centroidLon = entries.get(i).longitude;
                clusterSize = 1;
            } else {
                // Update running centroid using incremental mean
                clusterSize++;
                centroidLat += (entries.get(i).latitude - centroidLat) / clusterSize;
                centroidLon += (entries.get(i).longitude - centroidLon) / clusterSize;
            }
        }

        // Check last cluster
        long duration = entries.get(entries.size() - 1).timestamp - entries.get(clusterStart).timestamp;
        if (duration >= minDurationMs) {
            destinations.add(buildDestination(entries, clusterStart, entries.size() - 1));
        }

        return destinations;
    }

    private static Destination buildDestination(List<LocationEntry> entries, int startIdx, int endIdx) {
        Destination dest = new Destination();
        double sumLat = 0, sumLon = 0, sumAlt = 0;
        int count = endIdx - startIdx + 1;

        for (int j = startIdx; j <= endIdx; j++) {
            sumLat += entries.get(j).latitude;
            sumLon += entries.get(j).longitude;
            sumAlt += entries.get(j).altitude;
        }

        dest.latitude = sumLat / count;
        dest.longitude = sumLon / count;
        dest.avgAltitude = sumAlt / count;
        dest.arrivalTime = entries.get(startIdx).timestamp;
        dest.departureTime = entries.get(endIdx).timestamp;
        dest.durationMs = dest.departureTime - dest.arrivalTime;
        dest.pointCount = count;
        dest.startIndex = startIdx;
        dest.endIndex = endIdx;
        dest.address = "N/A";

        return dest;
    }

    public static List<Commute> findCommutes(List<LocationEntry> entries,
                                              List<Destination> destinations) {
        List<Commute> commutes = new ArrayList<>();
        if (destinations == null || destinations.size() < 2 || entries == null || entries.isEmpty()) {
            return commutes;
        }

        for (int d = 0; d < destinations.size() - 1; d++) {
            Destination from = destinations.get(d);
            Destination to = destinations.get(d + 1);

            long commuteStart = from.departureTime;
            long commuteEnd = to.arrivalTime;

            // Collect points between the two destinations
            List<LocationEntry> segment = new ArrayList<>();
            for (int i = from.endIndex; i <= to.startIndex && i < entries.size(); i++) {
                LocationEntry e = entries.get(i);
                if (e.timestamp >= commuteStart && e.timestamp <= commuteEnd) {
                    segment.add(e);
                }
            }

            if (segment.size() < 2) {
                continue;
            }

            Commute commute = new Commute();
            commute.startLat = segment.get(0).latitude;
            commute.startLon = segment.get(0).longitude;
            commute.startAltitude = segment.get(0).altitude;
            commute.endLat = segment.get(segment.size() - 1).latitude;
            commute.endLon = segment.get(segment.size() - 1).longitude;
            commute.endAltitude = segment.get(segment.size() - 1).altitude;
            commute.startTime = segment.get(0).timestamp;
            commute.endTime = segment.get(segment.size() - 1).timestamp;
            commute.durationMs = commute.endTime - commute.startTime;
            commute.pointCount = segment.size();
            commute.startAddress = from.address;
            commute.endAddress = to.address;

            double totalDistance = 0;
            double elevGain = 0;
            double elevLoss = 0;
            float[] distResult = new float[1];

            for (int i = 1; i < segment.size(); i++) {
                LocationEntry prev = segment.get(i - 1);
                LocationEntry curr = segment.get(i);

                Location.distanceBetween(
                        prev.latitude, prev.longitude,
                        curr.latitude, curr.longitude,
                        distResult);
                totalDistance += distResult[0];

                double altDelta = curr.altitude - prev.altitude;
                if (altDelta > 0) {
                    elevGain += altDelta;
                } else {
                    elevLoss += Math.abs(altDelta);
                }
            }

            commute.distanceMeters = totalDistance;
            commute.elevationGainMeters = elevGain;
            commute.elevationLossMeters = elevLoss;

            commutes.add(commute);
        }

        return commutes;
    }

    public static String reverseGeocode(Context context, double lat, double lon) {
        if (!Geocoder.isPresent()) {
            return "N/A";
        }
        try {
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address addr = addresses.get(0);
                String line = addr.getAddressLine(0);
                if (line != null && !line.isEmpty()) {
                    return line;
                }
                // Fallback to feature name + locality
                StringBuilder sb = new StringBuilder();
                if (addr.getFeatureName() != null) {
                    sb.append(addr.getFeatureName());
                }
                if (addr.getLocality() != null) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(addr.getLocality());
                }
                return sb.length() > 0 ? sb.toString() : "N/A";
            }
        } catch (IOException e) {
            // Geocoder unavailable or network error
        }
        return "N/A";
    }
}
