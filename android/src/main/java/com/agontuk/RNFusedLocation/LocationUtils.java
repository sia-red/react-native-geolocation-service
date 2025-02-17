package com.agontuk.RNFusedLocation;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.Manifest;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import rapid.deployment.location.events.HeartBeatEvent;
import rapid.deployment.location.utils.LocationConstant;

public class LocationUtils {
  /**
   * Calculates the age of a location fix in milliseconds
   */
  public static long getLocationAge(Location location) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      return (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000;
    }

    return System.currentTimeMillis() - location.getTime();
  }

  /**
   * Check if location permissions are granted.
   */
  public static boolean hasLocationPermission(Context context) {
    return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
      ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * Check if google play service is available on device.
   */
  public static boolean isGooglePlayServicesAvailable(Context context) {
    int result = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);

    // TODO: Handle other possible success types.
    return result == ConnectionResult.SUCCESS || result == ConnectionResult.SERVICE_UPDATING;
  }

  /**
   * Check if airplane mode is on/off
   */
  @SuppressWarnings("deprecation")
  public static boolean isOnAirplaneMode(Context context) {
    ContentResolver contentResolver = context.getContentResolver();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      return Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    return Settings.System.getInt(contentResolver, Settings.System.AIRPLANE_MODE_ON, 0) != 0;
  }

  /**
   * Check if location is enabled on the device.
   */
  public static boolean isLocationEnabled(Context context) {
    int locationMode;
    String locationProviders;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      try {
        locationMode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE);
      } catch (Settings.SettingNotFoundException e) {
        return false;
      }

      return locationMode != Settings.Secure.LOCATION_MODE_OFF;
    } else {
      locationProviders = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
      return !TextUtils.isEmpty(locationProviders);
    }
  }

  /**
   * Check if a specific location provider is enabled or not
   */
  public static boolean isProviderEnabled(Context context, String provider) {
    try {
      LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
      return lm.isProviderEnabled(provider);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Build error response for error callback.
   */
  public static WritableMap buildError(LocationError locationError, @Nullable String message) {
    String msg = message;

    if (msg == null) {
      msg = getDefaultErrorMessage(locationError);
    }

    WritableMap error = Arguments.createMap();
    error.putInt("code", locationError.getValue());
    error.putString("message", msg);

    return error;
  }

  public static WritableMap locationToMap(Location location) {
    WritableMap map = Arguments.createMap();
    WritableMap coords = Arguments.createMap();

    coords.putDouble("latitude", location.getLatitude());
    coords.putDouble("longitude", location.getLongitude());
    coords.putDouble("altitude", location.getAltitude());
    coords.putDouble("accuracy", location.getAccuracy());
    coords.putDouble("heading", location.getBearing());
    coords.putDouble("speed", location.getSpeed());

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      coords.putDouble("altitudeAccuracy", location.getVerticalAccuracyMeters());
    }

    map.putMap("coords", coords);
    map.putString("provider", location.getProvider());
    map.putDouble("timestamp", location.getTime());

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
      map.putBoolean("mocked", location.isFromMockProvider());
    }

    return map;
  }

  private static String getDefaultErrorMessage(LocationError locationError) {
    switch (locationError) {
      case PERMISSION_DENIED:
        return "Location permission not granted.";
      case POSITION_UNAVAILABLE:
        return "No location provider available.";
      case TIMEOUT:
        return "Location request timed out.";
      case PLAY_SERVICE_NOT_AVAILABLE:
        return "Google play service is not available.";
      case SETTINGS_NOT_SATISFIED:
        return "Location settings are not satisfied.";
      case INTERNAL_ERROR:
      default:
        return "Internal error occurred";
    }
  }

  public static WritableMap heartBeatToMap(HeartBeatEvent heartBeatEvent){
    WritableMap map = Arguments.createMap();

    map.putDouble("timestamp", heartBeatEvent.getTime().doubleValue());

    return map;
  }
}
