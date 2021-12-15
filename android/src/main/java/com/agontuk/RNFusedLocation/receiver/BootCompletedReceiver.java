package com.agontuk.RNFusedLocation.receiver;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.agontuk.RNFusedLocation.LocationUtils;
import com.agontuk.RNFusedLocation.RNFusedLocationModule;
import com.agontuk.RNFusedLocation.service.LocationHeadlessJsTaskService;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import rapid.deployment.location.LocationModule;
import rapid.deployment.location.LocationService;
import rapid.deployment.location.events.HeartBeatEvent;
import rapid.deployment.utils.UtilsConstant;
import rapid.deployment.utils.UtilsHelper;
import rapid.deployment.utils.cipher.Cipher;
import rapid.deployment.utils.cipher.SecurePreferences;

import com.facebook.react.HeadlessJsTaskService;

public class BootCompletedReceiver extends BroadcastReceiver {

    private Context mContext;

    private long mLocationInterval = RNFusedLocationModule.DEFAULT_INTERVAL;
    private long mFastestLocationInterval = RNFusedLocationModule.DEFAULT_FASTEST_INTERVAL;
    private int mMinLocationAccuracy = RNFusedLocationModule.DEFAULT_MIN_LOCATION_ACCURACY;
    private long mRouteInterval = RNFusedLocationModule.DEFAULT_ROUTE_INTERVAL;
    private long mRouteFastestInterval = RNFusedLocationModule.DEFAULT_ROUTE_FASTEST_INTERVAL;

    private boolean mLocationAsService = false;
    private boolean mLocationServiceAsForeground = false;
    private boolean mShouldAcceptMockLocation = false;
    private String mForegroundNotificationTitle = "Foreground Notification Title";
    private String mForegroundNotificationDescription = "Foreground Notification Description";
    private String mForegroundNotificationSmallIcon = "ic_launcher";
    private String mForegroundNotificationLargeIcon = "ic_launcher";
    private String mForegroundNotificationColor = RNFusedLocationModule.DEFAULT_NOTIFICATION_COLOR;
    private Class mNotificationBroadcastReceiverClass = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        //Log.d("BOOT_COMPLETED", "BOOT_COMPLETED2");

        mContext = context;
        loadServiceParameters(context);

        if (mLocationAsService) {
            LocationModule.initialize(new LocationModule.Configuration.Builder()
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
                    // .notificationBroadcastReceiverClass(NotificationBroadcastReceiver.class)
                    // .requestAccessLocationActivityIntent(new Intent(this,LaunchActivity.class))
                    .shouldAcceptMockLocation(mShouldAcceptMockLocation).build());

            EventBus.getDefault().register(this);

           this.utilStartService(context, LocationService.class, mLocationServiceAsForeground);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onLocationEvent(Location location) {
        //Log.d("SIA_PS_LOG", "Location: " + String.valueOf(location.getLatitude()) + "," + String.valueOf(location.getLongitude()));
        startHeadlessService(mContext, LocationUtils.locationToMap(location));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onHeartBeatEvent(HeartBeatEvent heartBeatEvent) {
        //Log.d("SIA_PS_LOG", "Heartbeat: " + String.valueOf(heartBeatEvent.getTime()));
        startHeadlessService(mContext, LocationUtils.heartBeatToMap(heartBeatEvent));
    }

    private void startHeadlessService(Context context, WritableMap data) {
        if (context != null && !isAppOnForeground((context))) {
            Intent serviceIntent = new Intent(context, LocationHeadlessJsTaskService.class);

            Bundle bundle = Arguments.toBundle(data);

            if (bundle == null)
                return;

            serviceIntent.putExtras(bundle);

            context.startService(serviceIntent);
            HeadlessJsTaskService.acquireWakeLockNow(context);
        }
    }

    private void loadServiceParameters(Context context) {
        SecurePreferences securePreferences = Cipher.getInstance().getSecureSharedPreferencesSessionLess(context,
                UtilsConstant.NOSESSION_SR_SHARED_PREF_NAME);
        mLocationAsService = securePreferences.getBoolean("locationAsService", mLocationAsService);
        mLocationServiceAsForeground = securePreferences.getBoolean("locationServiceAsForeground", mLocationServiceAsForeground);
        mShouldAcceptMockLocation = securePreferences.getBoolean("shouldServiceAcceptMockLocation", mShouldAcceptMockLocation);
        mMinLocationAccuracy = securePreferences.getInt("minLocationAccuracy", mMinLocationAccuracy);
        mLocationInterval = securePreferences.getLong("locationInterval", mLocationInterval);
        mFastestLocationInterval = securePreferences.getLong("fastestLocationInterval", mFastestLocationInterval);
        mRouteInterval = securePreferences.getLong("routeInterval", mRouteInterval);
        mRouteFastestInterval = securePreferences.getLong("routeFastestInterval", mRouteFastestInterval);
        mForegroundNotificationTitle = securePreferences.getString("foregroundNotificationTitle", mForegroundNotificationTitle);
        mForegroundNotificationDescription = securePreferences.getString("foregroundNotificationDescription", mForegroundNotificationDescription);
        mForegroundNotificationSmallIcon = !securePreferences.getString("foregroundNotificationSmallIcon", "").isEmpty() ? mForegroundNotificationSmallIcon : "ic_launcher";
        mForegroundNotificationLargeIcon = !securePreferences.getString("foregroundNotificationLargeIcon", "").isEmpty() ? mForegroundNotificationLargeIcon : "ic_launcher";
        mForegroundNotificationColor = !securePreferences.getString("foregroundNotificationColor", "").isEmpty() ? mForegroundNotificationColor : RNFusedLocationModule.DEFAULT_NOTIFICATION_COLOR;
        if (!securePreferences.getString("notificationBroadcastReceiverClassName", "").isEmpty()){
            try {
                mNotificationBroadcastReceiverClass = Class.forName(securePreferences.getString("notificationBroadcastReceiverClassName", ""));
            }catch (ClassNotFoundException ignored){}
        }
    }

    private boolean isAppOnForeground(Context context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        if (activityManager == null)
            return false;

        List<ActivityManager.RunningAppProcessInfo> appProcesses =
                activityManager.getRunningAppProcesses();
        if (appProcesses == null) {
            return false;
        }
        final String packageName = context.getPackageName();
        for (ActivityManager.RunningAppProcessInfo appProcess : appProcesses) {
            if (appProcess.importance ==
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    appProcess.processName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private void utilStartService(Context context, Class<?> serviceClass, boolean serviceAsForeground) {
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

    private boolean isServiceRunning(ActivityManager manager, Class<?> serviceClass) {
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

}
