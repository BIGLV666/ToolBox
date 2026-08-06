package com.example.toolbox.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Uses Android's enabled location providers to obtain a recent device location.
 *
 * <p>The provider waits briefly for a current fix, keeps the best candidate received from GPS
 * or network providers, and falls back to the best last-known fix when the timeout expires.</p>
 */
public final class DeviceLocationProvider {
    private static final long TIMEOUT_MS = 10_000L;
    private static final long MAX_LAST_KNOWN_AGE_MS = 6L * 60 * 60 * 1000;
    private static final float GOOD_ACCURACY_METERS = 150f;

    public interface Callback {
        void onResult(Location location);
    }

    private final Context context;
    private final LocationManager locationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Callback callback;
    private Location bestLocation;

    private final Runnable timeout = () -> finish(bestLocation);
    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (isBetter(location, bestLocation)) bestLocation = location;
            if (location.hasAccuracy() && location.getAccuracy() <= GOOD_ACCURACY_METERS) {
                finish(bestLocation);
            }
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
    };

    public DeviceLocationProvider(Context context) {
        this.context = context.getApplicationContext();
        locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void requestCurrentLocation(Callback callback) {
        cancel();
        this.callback = callback;
        if (!hasPermission(context) || locationManager == null) {
            finish(null);
            return;
        }

        bestLocation = findBestLastKnownLocation();
        boolean requested = false;
        boolean fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        try {
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper());
                requested = true;
            }
        } catch (RuntimeException ignored) { }
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper());
                requested = true;
            }
        } catch (RuntimeException ignored) { }

        if (!requested) {
            finish(bestLocation);
            return;
        }
        mainHandler.postDelayed(timeout, TIMEOUT_MS);
    }

    public void cancel() {
        mainHandler.removeCallbacks(timeout);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(listener);
            } catch (RuntimeException ignored) { }
        }
        callback = null;
        bestLocation = null;
    }

    private Location findBestLastKnownLocation() {
        Location best = null;
        try {
            List<String> providers = locationManager.getProviders(true);
            for (String provider : providers) {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location != null && System.currentTimeMillis() - location.getTime() > MAX_LAST_KNOWN_AGE_MS) {
                    continue;
                }
                if (isBetter(location, best)) best = location;
            }
        } catch (SecurityException ignored) { }
        return best;
    }

    private void finish(Location location) {
        Callback target = callback;
        mainHandler.removeCallbacks(timeout);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(listener);
            } catch (RuntimeException ignored) { }
        }
        callback = null;
        bestLocation = null;
        if (target != null) target.onResult(location);
    }

    private static boolean isBetter(Location candidate, Location current) {
        if (candidate == null) return false;
        if (current == null) return true;
        long timeDelta = candidate.getTime() - current.getTime();
        if (timeDelta > 120_000L) return true;
        if (timeDelta < -120_000L) return false;
        float candidateAccuracy = candidate.hasAccuracy() ? candidate.getAccuracy() : Float.MAX_VALUE;
        float currentAccuracy = current.hasAccuracy() ? current.getAccuracy() : Float.MAX_VALUE;
        return candidateAccuracy < currentAccuracy || (timeDelta > 0 && candidateAccuracy <= currentAccuracy * 1.5f);
    }
}
