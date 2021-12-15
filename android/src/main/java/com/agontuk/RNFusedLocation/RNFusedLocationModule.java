package com.agontuk.RNFusedLocation;

import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter;

import java.util.Collection;
import java.util.HashMap;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentSender.SendIntentException;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.app.ActivityManager;

import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.common.SystemClock;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.RuntimeException;

import rapid.deployment.location.events.HeartBeatEvent;
import rapid.deployment.location.LocationModule;
import rapid.deployment.location.LocationService;
import rapid.deployment.utils.UtilsConstant;
import rapid.deployment.utils.UtilsHelper;
import rapid.deployment.utils.cipher.Cipher;
import rapid.deployment.utils.cipher.SecurePreferences;

import static android.content.Context.POWER_SERVICE;

public class RNFusedLocationModule extends ReactContextBaseJavaModule implements ActivityEventListener {
  public static final String TAG = "RNFusedLocation";
  private int singleLocationProviderKeyCounter = 1;
  private final HashMap<String, LocationProvider> singleLocationProviders;
  @Nullable private LocationProvider continuousLocationProvider;
  private static final int REQUEST_SETTINGS_SINGLE_UPDATE = 11403;
  private static final int REQUEST_SETTINGS_CONTINUOUS_UPDATE = 11404;
  private static final float DEFAULT_DISTANCE_FILTER = 100;
  public static final int DEFAULT_ACCURACY = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
  public static final long DEFAULT_INTERVAL = 10 * 1000; /* 10 secs */
  public static final long DEFAULT_FASTEST_INTERVAL = 5 * 1000; /* 5 sec */

  public static final long DEFAULT_ROUTE_INTERVAL = 5 * 1000; /* 5 secs */
  public static final long DEFAULT_ROUTE_FASTEST_INTERVAL = 1000; /* 1 sec */

  public static final int DEFAULT_MIN_LOCATION_ACCURACY = 50; /* 50 mts */

  public static final String DEFAULT_NOTIFICATION_COLOR = "#464646";

  private boolean mShowLocationDialog = true;
  private int mLocationPriority = DEFAULT_ACCURACY;
  private long mLocationInterval = DEFAULT_INTERVAL;
  private long mFastestLocationInterval = DEFAULT_FASTEST_INTERVAL;
  private double mMaximumAge = Double.POSITIVE_INFINITY;
  private long mTimeout = Long.MAX_VALUE;
  private float mDistanceFilter = DEFAULT_DISTANCE_FILTER;
  private int mMinLocationAccuracy = DEFAULT_MIN_LOCATION_ACCURACY;
  private long mRouteInterval = DEFAULT_ROUTE_INTERVAL;
  private long mRouteFastestInterval = DEFAULT_ROUTE_FASTEST_INTERVAL;

  private boolean mLocationAsService = false;
  private boolean mLocationServiceAsForeground = false;
  private boolean mIgnoredBatteryOptimization = false;
  private boolean mShouldAcceptMockLocation = false;
  private String mForegroundNotificationTitle = "Foreground Notification Title";
  private String mForegroundNotificationDescription = "Foreground Notification Description";
  private String mForegroundNotificationSmallIcon = "ic_launcher";
  private String mForegroundNotificationLargeIcon = "ic_launcher";
  private String mForegroundNotificationColor = DEFAULT_NOTIFICATION_COLOR;
  private Class mNotificationBroadcastReceiverClass = null;

  private Callback mSuccessCallback;
  private Callback mErrorCallback;
  private FusedLocationProviderClient mFusedProviderClient;
  private SettingsClient mSettingsClient;
  private LocationRequest mLocationRequest;
  private LocationCallback mLocationCallback;

  private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {
    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent intent) {
        if (requestCode == REQUEST_SETTINGS_SINGLE_UPDATE) {
            if (resultCode == Activity.RESULT_CANCELED) {
                // User cancelled the request.
                // TODO: allow user to ignore this & request location.
                invokeError(LocationError.SETTINGS_NOT_SATISFIED.getValue(), "Location settings are not satisfied.",
                        true);
            } else if (resultCode == Activity.RESULT_OK) {
                // Location settings changed successfully, request user location.
                getUserLocation();
            }
        } else if (requestCode == REQUEST_SETTINGS_CONTINUOUS_UPDATE) {
            if (resultCode == Activity.RESULT_CANCELED) {
                // User cancelled the request.
                // TODO: allow user to ignore this & request location.
                invokeError(LocationError.SETTINGS_NOT_SATISFIED.getValue(), "Location settings are not satisfied.",
                        false);
            } else if (resultCode == Activity.RESULT_OK) {
                // Location settings changed successfully, request user location.
                getLocationUpdates();
            }
        }
    }
  };

  public RNFusedLocationModule(ReactApplicationContext reactContext) {
    super(reactContext);

    this.mFusedProviderClient = LocationServices.getFusedLocationProviderClient(reactContext);
    this.mSettingsClient = LocationServices.getSettingsClient(reactContext);
    reactContext.addActivityEventListener(mActivityEventListener);
    this.singleLocationProviders = new HashMap<>();

    Log.i(TAG, TAG + " initialized");
  }

  @NonNull
  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
    if (continuousLocationProvider != null &&
      continuousLocationProvider.onActivityResult(requestCode, resultCode)
    ) {
      return;
    }

    Collection<LocationProvider> providers = singleLocationProviders.values();

    for (LocationProvider locationProvider: providers) {
      if (locationProvider.onActivityResult(requestCode, resultCode)) {
        return;
      }
    }
  }

  @Override
  public void onNewIntent(Intent intent) {
    //
  }

  @ReactMethod
  public void getCurrentPosition(ReadableMap options, final Callback success, final Callback error) {
    ReactApplicationContext context = getContext();

    mSuccessCallback = success;
    mErrorCallback = error;

    if (!LocationUtils.hasLocationPermission(context)) {
      error.invoke(LocationUtils.buildError(LocationError.PERMISSION_DENIED, null));
      return;
    }

    LocationOptions locationOptions = LocationOptions.fromReadableMap(options);
    final LocationProvider locationProvider = createLocationProvider(locationOptions.isForceLocationManager());

    final String key = "provider-" + singleLocationProviderKeyCounter;
    singleLocationProviders.put(key, locationProvider);
    singleLocationProviderKeyCounter++;

    locationProvider.getCurrentLocation(locationOptions, new LocationChangeListener() {
      @Override
      public void onLocationChange(Location location) {
        success.invoke(LocationUtils.locationToMap(location));
        singleLocationProviders.remove(key);
      }

      @Override
      public void onLocationError(LocationError locationError, @Nullable String message) {
        error.invoke(LocationUtils.buildError(locationError, message));
        singleLocationProviders.remove(key);
      }
    });

    boolean highAccuracy = options.hasKey("enableHighAccuracy") && options.getBoolean("enableHighAccuracy");

    // TODO: Make other PRIORITY_* constants availabe to the user
    mLocationPriority = highAccuracy ? LocationRequest.PRIORITY_HIGH_ACCURACY : DEFAULT_ACCURACY;
    mTimeout = options.hasKey("timeout") ? (long) options.getDouble("timeout") : Long.MAX_VALUE;
    mMaximumAge = options.hasKey("maximumAge") ? options.getDouble("maximumAge") : Double.POSITIVE_INFINITY;
    mDistanceFilter = options.hasKey("distanceFilter") ? (float) options.getDouble("distanceFilter") : 0;
    mShowLocationDialog = !options.hasKey("showLocationDialog") || options.getBoolean("showLocationDialog");
    mShouldAcceptMockLocation = options.hasKey("shouldAcceptMockLocation") && options.getBoolean("shouldAcceptMockLocation");
  }

  @ReactMethod
  public void startObserving(ReadableMap options) {
    ReactApplicationContext context = getContext();

    if (!LocationUtils.hasLocationPermission(context)) {
      emitEvent(
        "geolocationError",
        LocationUtils.buildError(LocationError.PERMISSION_DENIED, null)
      );
      return;
    }

    LocationOptions locationOptions = LocationOptions.fromReadableMap(options);

    if (continuousLocationProvider == null) {
      continuousLocationProvider = createLocationProvider(locationOptions.isForceLocationManager());
    }

    continuousLocationProvider.requestLocationUpdates(locationOptions, new LocationChangeListener() {
      @Override
      public void onLocationChange(Location location) {
        emitEvent("geolocationDidChange", LocationUtils.locationToMap(location));
      }

      @Override
      public void onLocationError(LocationError error, @Nullable String message) {
        emitEvent("geolocationError", LocationUtils.buildError(error, message));
      }
    });

    boolean highAccuracy = options.hasKey("enableHighAccuracy") && options.getBoolean("enableHighAccuracy");

    // TODO: Make other PRIORITY_* constants availabe to the user
    mLocationPriority = highAccuracy ? LocationRequest.PRIORITY_HIGH_ACCURACY : DEFAULT_ACCURACY;
    mDistanceFilter = options.hasKey("distanceFilter") ? (float) options.getDouble("distanceFilter")
            : DEFAULT_DISTANCE_FILTER;
    mLocationInterval = options.hasKey("interval") ? (long) options.getDouble("interval") : DEFAULT_INTERVAL;
    mFastestLocationInterval = options.hasKey("fastestInterval") ? (long) options.getDouble("fastestInterval")
            : DEFAULT_INTERVAL;

    mRouteInterval = options.hasKey("routeInterval") ? (long) options.getDouble("routeInterval")
            : DEFAULT_ROUTE_INTERVAL;

    mRouteFastestInterval = options.hasKey("routeFastestInterval")
            ? (long) options.getDouble("routeFastestInterval")
            : DEFAULT_ROUTE_FASTEST_INTERVAL;

    mMinLocationAccuracy = options.hasKey("minLocationAccuracy") ? options.getInt("minLocationAccuracy")
            : DEFAULT_MIN_LOCATION_ACCURACY;

    mShowLocationDialog = !options.hasKey("showLocationDialog") || options.getBoolean("showLocationDialog");

    mLocationAsService = options.hasKey("asService") && options.getBoolean("asService");
    mLocationServiceAsForeground = options.hasKey("serviceAsForeground") && options.getBoolean("serviceAsForeground");
    mIgnoredBatteryOptimization = options.hasKey("ignoredBatteryOptimization")
            && options.getBoolean("ignoredBatteryOptimization");
    mShouldAcceptMockLocation = options.hasKey("shouldAcceptMockLocation") && options.getBoolean("shouldAcceptMockLocation");

    mForegroundNotificationTitle = options.hasKey("foregroundNotificationTitle")
            ? options.getString("foregroundNotificationTitle")
            : "Foreground Notification Title";
    mForegroundNotificationDescription = options.hasKey("foregroundNotificationDescription")
            ? options.getString("foregroundNotificationDescription")
            : "Foreground Notification Description";

    if (options.hasKey("foregroundNotificationSmallIcon")) {
        mForegroundNotificationSmallIcon =
                options.getString("foregroundNotificationSmallIcon");
    } else {
        mForegroundNotificationSmallIcon = "ic_launcher";
    }

    if (options.hasKey("foregroundNotificationLargeIcon")) {
        mForegroundNotificationLargeIcon =
                options.getString("foregroundNotificationLargeIcon");
    } else {
        mForegroundNotificationLargeIcon = "ic_launcher";
    }

    if (options.hasKey("foregroundNotificationColor")) {
        mForegroundNotificationColor = options.getString("foregroundNotificationColor");
    } else {
        mForegroundNotificationColor = DEFAULT_NOTIFICATION_COLOR;
    }

    if (options.hasKey("notificationBroadcastReceiverClassName")) {
        try {
            mNotificationBroadcastReceiverClass = Class.forName((options.getString("notificationBroadcastReceiverClassName")));
        }catch (ClassNotFoundException ignored){}
    }

    if (!mShouldAcceptMockLocation) {
        LocationSettingsRequest locationSettingsRequest = buildLocationSettingsRequest();
        if (mSettingsClient != null) {
            mSettingsClient.checkLocationSettings(locationSettingsRequest)
                    .addOnCompleteListener(new OnCompleteListener<LocationSettingsResponse>() {
                        @Override
                        public void onComplete(Task<LocationSettingsResponse> task) {
                            onLocationSettingsResponse(task, false);
                        }
                    });
        }
    }else {
        getLocationUpdates();
    }
    
    saveServiceParameters();
  }

  @ReactMethod
  public void stopObserving() {
    if (continuousLocationProvider != null) {
      continuousLocationProvider.removeLocationUpdates();
      continuousLocationProvider = null;
    }

    loadServiceParameters();
    if (!mLocationAsService) {
        if (mFusedProviderClient != null && mLocationCallback != null) {
            mFusedProviderClient.removeLocationUpdates(mLocationCallback);
            mLocationCallback = null;
        }
    } else {
        //must be done before stopService call.
        mLocationAsService = false;
        SecurePreferences securePreferences = Cipher.getInstance().getSecureSharedPreferencesSessionLess(getContext(),
                UtilsConstant.NOSESSION_SR_SHARED_PREF_NAME);
        if (securePreferences.getBoolean("locationAsService", false)) {
            SecurePreferences.Editor editor = securePreferences.edit();
            editor.remove("locationAsService");
            editor.commit();
        }

        stopService();
    }
  }

  @ReactMethod
  public void addListener(String eventName) {
    // Keep: Required for RN built in Event Emitter Calls.
  }

  @ReactMethod
  public void removeListeners(Integer count) {
    // Keep: Required for RN built in Event Emitter Calls.
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void onLocationEvent(Location location) {
      invokeSuccess(LocationUtils.locationToMap(location), false);
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  public void onHeartBeatEvent(HeartBeatEvent heartBeatEvent) {
      invokeSuccess(LocationUtils.heartBeatToMap(heartBeatEvent), false);
  }

   /**
     * Clear the JS callbacks
     */
    private void clearCallbacks() {
      mSuccessCallback = null;
      mErrorCallback = null;
  }

  /**
   * Helper method to invoke success callback
   */
  private void invokeSuccess(WritableMap data, boolean isSingleUpdate) {
      if (!isSingleUpdate) {
          getContext().getJSModule(RCTDeviceEventEmitter.class).emit("geolocationDidChange", data);
          return;
      }

      try {
          if (mSuccessCallback != null) {
              mSuccessCallback.invoke(data);
          }

          clearCallbacks();
      } catch (RuntimeException e) {
          // Illegal callback invocation
          Log.w(TAG, e.getMessage());
      }
  }

  private LocationProvider createLocationProvider(boolean forceLocationManager) {
    ReactApplicationContext context = getContext();
    boolean playServicesAvailable = LocationUtils.isGooglePlayServicesAvailable(context);

    if (forceLocationManager || !playServicesAvailable) {
      return new LocationManagerProvider(context);
    }

    return new FusedLocationProvider(context);
  }

  private void emitEvent(String eventName, WritableMap data) {
    getContext().getJSModule(RCTDeviceEventEmitter.class).emit(eventName, data);
  }

  private ReactApplicationContext getContext() {
    return getReactApplicationContext();
  }

  /*
  * Check location setting response and decide whether to proceed with location
  * request or not.
  */
  private void onLocationSettingsResponse(Task<LocationSettingsResponse> task, boolean isSingleUpdate) {
      try {
          LocationSettingsResponse response = task.getResult(ApiException.class);          
          getLocationUpdates();
      } catch (ApiException exception) {
          switch (exception.getStatusCode()) {
          case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
              /**
               * Location settings are not satisfied. But could be fixed by showing the user a
               * dialog. It means either location serivce is not enabled or default location
               * mode is not enough to perform the request.
               */
              if (!mShowLocationDialog) {
                invokeError(LocationError.SETTINGS_NOT_SATISFIED, "Location settings are not satisfied.", isSingleUpdate);
                  break;
              }

              try {
                  ResolvableApiException resolvable = (ResolvableApiException) exception;
                  Activity activity = getCurrentActivity();

                  if (activity == null) {
                      invokeError(LocationError.INTERNAL_ERROR,
                              "Tried to open location dialog while not attached to an Activity", isSingleUpdate);
                      break;
                  }

                  resolvable.startResolutionForResult(activity,
                          isSingleUpdate ? REQUEST_SETTINGS_SINGLE_UPDATE : REQUEST_SETTINGS_CONTINUOUS_UPDATE);
              } catch (SendIntentException e) {
                  invokeError(LocationError.INTERNAL_ERROR, "Internal error occurred",isSingleUpdate);
              } catch (ClassCastException e) {
                  invokeError(LocationError.INTERNAL_ERROR, "Internal error occurred",isSingleUpdate);
              }

              break;
          default:
              // TODO: we may have to handle other use case here.
              // For now just say that settings are not ok.
              invokeError(LocationError.SETTINGS_NOT_SATISFIED, "Location settings are not satisfied.",
                      isSingleUpdate);

              break;
          }
      }
  }

  private boolean isServiceRunning(ActivityManager manager, Class<?> serviceClass) {
    for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
        if (serviceClass.getName().equals(service.service.getClassName())) {
            return true;
        }
    }
    return false;
  }

  /*
  * Get periodic location updates based on the current location request.
  */
  @SuppressLint("MissingPermission")
  private void getLocationUpdates() {
        Context context = getContext();
        if (!mLocationAsService) {
            SecurePreferences securePreferences = Cipher.getInstance()
                    .getSecureSharedPreferencesSessionLess(getContext(),
                            UtilsConstant.NOSESSION_SR_SHARED_PREF_NAME);
            if (securePreferences.getBoolean("locationAsService", false)) {
                SecurePreferences.Editor editor = securePreferences.edit();
                editor.remove("locationAsService");
                editor.apply();
            }

            if (mFusedProviderClient != null && mLocationRequest != null) {
                mLocationCallback = new LocationCallback() {
                    @Override
                    public void onLocationResult(LocationResult locationResult) {
                        SecurePreferences securePreferences = Cipher.getInstance()
                                .getSecureSharedPreferencesSessionLess(getContext(),
                                        UtilsConstant.NOSESSION_SR_SHARED_PREF_NAME);
                        if (securePreferences.getBoolean("locationAsService", false)) {
                            SecurePreferences.Editor editor = securePreferences.edit();
                            editor.remove("locationAsService");
                            editor.apply();
                        }

                        Location location = locationResult.getLastLocation();
                        invokeSuccess(LocationUtils.locationToMap(location), false);
                    }
                };

                mFusedProviderClient.requestLocationUpdates(mLocationRequest, mLocationCallback, null);
            }
        } else {

            if (mForegroundNotificationTitle == null || mForegroundNotificationDescription == null || mForegroundNotificationSmallIcon == null ||
                    mForegroundNotificationLargeIcon == null){
                loadServiceParameters();
            }

            LocationModule.Configuration.Builder builder = new LocationModule.Configuration.Builder()
                    .minLocationAccuracy(mMinLocationAccuracy)
                    .locationInterval(Math.round(mLocationInterval / 1000))
                    .locationFastestInterval(Math.round(mFastestLocationInterval / 1000))
                    .routeInterval(Math.round(mRouteInterval / 1000))
                    .routeFastestInterval(Math.round(mRouteFastestInterval / 1000))
                    .foregroundNotificationTitle(mForegroundNotificationTitle)
                    .foregroundNotificationDescription(mForegroundNotificationDescription)
                    .notificationSmallIcon(context.getResources().getIdentifier(
                            mForegroundNotificationSmallIcon, "mipmap", context.getPackageName()))
                    .notificationLargeIcon(context.getResources().getIdentifier(
                            mForegroundNotificationLargeIcon, "mipmap", context.getPackageName()))
                    .notificationColor(Color.parseColor(mForegroundNotificationColor))
                    .shouldServiceBeInForeground(mLocationServiceAsForeground)
                    // .requestAccessLocationActivityIntent(new Intent(this,LaunchActivity.class))
                    .shouldAcceptMockLocation(mShouldAcceptMockLocation);

            if (mNotificationBroadcastReceiverClass != null){
                builder.notificationBroadcastReceiverClass(mNotificationBroadcastReceiverClass);
            }

            LocationModule.initialize(builder.build());

            EventBus.getDefault().register(this);

            //Log.d("ServiceRestarted","RNFusedLocationModule");
            startService(getContext().getApplicationContext(), mIgnoredBatteryOptimization, mLocationServiceAsForeground);
        }
  }

  /*
   * Stop service in case was activated.
  */
  @ReactMethod
  public void stopService() {
    EventBus.getDefault().unregister(this);
  }

  /*
   * Build location setting request using current configuration
   */
  private LocationSettingsRequest buildLocationSettingsRequest() {
    mLocationRequest = new LocationRequest();
    mLocationRequest.setPriority(mLocationPriority).setInterval(mLocationInterval)
            .setFastestInterval(mFastestLocationInterval).setSmallestDisplacement(mDistanceFilter);

    LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder();
    builder.addLocationRequest(mLocationRequest);

    return builder.build();
  }

    /**
     * Helper method to invoke error callback
     */
    private void invokeError(LocationError code, String message, boolean isSingleUpdate) {
      if (!isSingleUpdate) {
          getContext().getJSModule(RCTDeviceEventEmitter.class).emit("geolocationError",
                  LocationUtils.buildError(code, message));

          return;
      }

      try {
          if (mErrorCallback != null) {
              mErrorCallback.invoke(LocationUtils.buildError(code, message));
          }

          clearCallbacks();
      } catch (RuntimeException e) {
          // Illegal callback invocation
          Log.w(TAG, e.getMessage());
      }
  }

  @SuppressLint("BatteryLife")
  private void startService(Context context, boolean ignoredBatteryOptimization, boolean serviceAsForeground) {
      if (ignoredBatteryOptimization) {
          // Before we start the service, confirm that we have extra power usage
          // privileges.
          PowerManager pm = (PowerManager) context.getSystemService(POWER_SERVICE);
          Intent intent = new Intent();
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
              if (pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                  intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                  intent.setData(Uri.parse("package:" + context.getPackageName()));
                  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                  context.startActivity(intent);
              }
          }
      }
      utilStartService(context, LocationService.class, serviceAsForeground);
  }

  public void utilStartService(Context context, Class<?> serviceClass, boolean serviceAsForeground) {
      ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      if (activityManager == null) {
          Log.e("LOCATION_LOG", "Trying to start " + serviceClass.getName() + "service but context.getSystemService(Context.ACTIVITY_SERVICE) == null");
          return;
      }

      if (!isServiceRunning(activityManager, serviceClass)) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && serviceAsForeground) {
              context.startForegroundService(new Intent(context, serviceClass));
          } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O){
              context.startService(new Intent(context, serviceClass));
          }
      }
  }

  @SuppressLint("BatteryLife")
  private void restartService(Context context, boolean ignoredBatteryOptimization, boolean serviceAsForeground) {
      // Before we start the service, confirm that we have extra power usage
      // privileges.
      if (ignoredBatteryOptimization) {
          PowerManager pm = (PowerManager) context.getSystemService(POWER_SERVICE);
          Intent intent = new Intent();
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
              if (pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                  intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                  intent.setData(Uri.parse("package:" + context.getPackageName()));
                  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                  context.startActivity(intent);
              }
          }
      }
      utilsRestartService(context, LocationService.class, serviceAsForeground);
  }

  public void utilsRestartService(Context context, Class<?> serviceClass, boolean serviceAsForeground) {
    ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    if (activityManager == null) {
        Log.e(UtilsConstant.UTILS_LOG, "Trying to start " + serviceClass.getName() + "service but context.getSystemService(Context.ACTIVITY_SERVICE) == null");
        return;
    }

    if (isServiceRunning(activityManager, serviceClass)) {
        context.stopService(new Intent(context, serviceClass));
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && serviceAsForeground) {
        context.startForegroundService(new Intent(context, serviceClass));
    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O){
        context.startService(new Intent(context, serviceClass));
    }
  }

  private void loadServiceParameters(){
    SecurePreferences securePreferences = Cipher.getInstance().getSecureSharedPreferencesSessionLess(getContext(),
            UtilsConstant.NOSESSION_SR_SHARED_PREF_NAME);
    mLocationAsService = securePreferences.getBoolean("locationAsService",mLocationAsService);
    mLocationServiceAsForeground = securePreferences.getBoolean("locationServiceAsForeground",mLocationServiceAsForeground);
    mShouldAcceptMockLocation = securePreferences.getBoolean("shouldServiceAcceptMockLocation", mShouldAcceptMockLocation);
    mMinLocationAccuracy = securePreferences.getInt("minLocationAccuracy",mMinLocationAccuracy);
    mLocationInterval = securePreferences.getLong("locationInterval", mLocationInterval);
    mFastestLocationInterval = securePreferences.getLong("fastestLocationInterval", mFastestLocationInterval);
    mRouteInterval = securePreferences.getLong("routeInterval",mRouteInterval);
    mRouteFastestInterval = securePreferences.getLong("routeFastestInterval",mRouteFastestInterval);
    mForegroundNotificationTitle = securePreferences.getString("foregroundNotificationTitle",mForegroundNotificationTitle);
    mForegroundNotificationDescription = securePreferences.getString("foregroundNotificationDescription",mForegroundNotificationDescription);
    mForegroundNotificationSmallIcon = !securePreferences.getString("foregroundNotificationSmallIcon","").isEmpty() ? mForegroundNotificationSmallIcon : "ic_launcher";
    mForegroundNotificationLargeIcon = !securePreferences.getString("foregroundNotificationLargeIcon","").isEmpty() ? mForegroundNotificationLargeIcon : "ic_launcher";
    mForegroundNotificationColor = !securePreferences.getString("foregroundNotificationColor","").isEmpty() ? mForegroundNotificationColor : DEFAULT_NOTIFICATION_COLOR;
  }

  private void saveServiceParameters(){
    SecurePreferences securePreferences = Cipher.getInstance().getSecureSharedPreferencesSessionLess(getContext(),
            UtilsConstant.NOSESSION_SR_SHARED_PREF_NAME);
    SecurePreferences.Editor editor = securePreferences.edit();

    editor.putBoolean("locationAsService",mLocationAsService);
    editor.putBoolean("locationServiceAsForeground",mLocationServiceAsForeground);
    editor.putBoolean("shouldServiceAcceptMockLocation", mShouldAcceptMockLocation);
    editor.putInt("minLocationAccuracy",mMinLocationAccuracy);
    editor.putLong("locationInterval", mLocationInterval);
    editor.putLong("fastestLocationInterval", mFastestLocationInterval);
    editor.putLong("routeInterval",mRouteInterval);
    editor.putLong("routeFastestInterval",mRouteFastestInterval);
    editor.putString("foregroundNotificationTitle",mForegroundNotificationTitle);
    editor.putString("foregroundNotificationDescription",mForegroundNotificationDescription);
    editor.putString("foregroundNotificationSmallIcon",mForegroundNotificationSmallIcon);
    editor.putString("foregroundNotificationLargeIcon",mForegroundNotificationLargeIcon);
    editor.putString("foregroundNotificationColor",mForegroundNotificationColor);
    if (mNotificationBroadcastReceiverClass != null) {
        editor.putString("notificationBroadcastReceiverClassName", mNotificationBroadcastReceiverClass.getName());
    }
    editor.apply();
  }
}
