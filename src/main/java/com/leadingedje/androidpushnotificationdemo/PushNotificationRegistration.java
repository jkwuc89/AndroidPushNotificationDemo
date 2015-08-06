package com.leadingedje.androidpushnotificationdemo;

import java.io.IOException;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;

import android.app.Activity;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

/**
 * Push notification registration support
 */
public class PushNotificationRegistration extends IntentService {
    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;

    private static final String TAG = PushNotificationRegistration.class.getSimpleName();

    private static GoogleCloudMessaging gcm;

    public static final String SENT_TOKEN_TO_SERVER = "sentTokenToServer";
    public static final String REGISTRATION_COMPLETE = "registrationComplete";

    /**
     * Default constructor
     */
    public PushNotificationRegistration() {
        super(TAG);
    }

    /**
     * Check the device to make sure it has the Google Play Services APK. If it
     * doesn't, display a dialog that allows users to download the APK from the
     * Google Play Store or enable it in the device's system settings.
     *
     * @param activity {@link Activity} checking for availability of Google Play
     *                 Services
     * @return boolean true if Google Play Services are available, false otherwise
     */
    public static boolean isGooglePlayServicesAvailable(Activity activity) {
        boolean success = true;
        try {
            int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(activity);
            if(resultCode != ConnectionResult.SUCCESS) {
                //---------------------------------------------------------------------
                // If error is recoverable, display the error dialog that will
                // give the user the opportunity to install or update Google Play Services
                if(GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
                    Log.e(TAG,
                          "isGooglePlayServicesAvailable(): Google Play Services not availabe.  Prompting user to recover");
                    GooglePlayServicesUtil.getErrorDialog(resultCode, activity, PLAY_SERVICES_RESOLUTION_REQUEST)
                            .show();
                }
                else {
                    //---------------------------------------------------------------------
                    // Google Play Services not supported...finish the activity
                    Log.e(TAG,
                          "isGooglePlayServicesAvailable(): Google Play Services are not supported on this device.");
                    activity.finish();
                }
                success = false;
            }
        }
        catch(Exception exc) {
            Log.e(TAG, "isGooglePlayServicesAvailable(): isGooglePlayServicesAvailable exception: " + exc);
            success = false;
        }
        return success;
    }

    /**
     * Handle requests to register with GCM.
     * @param intent {@link Intent}
     */
    @Override
    protected void onHandleIntent(Intent intent) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        try {
            //---------------------------------------------------------------------
            // Handle multiple registration requests sequentially
            synchronized (TAG) {
                //---------------------------------------------------------------------
                // Initially this call goes out to the network to retrieve the token, subsequent calls
                // are local.
                InstanceID instanceID = InstanceID.getInstance(this);
                String token = instanceID.getToken(getString(R.string.gcm_defaultSenderId),
                                                   GoogleCloudMessaging.INSTANCE_ID_SCOPE, null);
                Log.i(TAG, "onHandleIntent(): GCM Registration Token: " + token);

                //---------------------------------------------------------------------
                // TODO: Implement this method to send any registration to your app's servers.
                // sendRegistrationToServer(token);

                //---------------------------------------------------------------------
                // Store a boolean to indicate that registration token was sent to the server
                sharedPreferences.edit().putBoolean(SENT_TOKEN_TO_SERVER, true).apply();
            }
        } catch (Exception e) {
            Log.d(TAG, "onHandleIntent(): Failed to complete GCM registration", e);
            //---------------------------------------------------------------------
            // Setting this flag ensures that we retry registration later
            sharedPreferences.edit().putBoolean(SENT_TOKEN_TO_SERVER, false).apply();
        }
        //---------------------------------------------------------------------
        // Notify UI that registration has completed
        Log.d(TAG, "onHandleIntent(): Notifying UI that GCM registration is complete");
        Intent registrationComplete = new Intent(REGISTRATION_COMPLETE);
        LocalBroadcastManager.getInstance(this).sendBroadcast(registrationComplete);
    }

    /**
     * Register for GCM
     * @param activity {@link Activity} that is registering
     */
    public static void register(Activity activity) {
        //---------------------------------------------------------------------
        // Start IntentService to register this application with GCM.
        Intent intent = new Intent(activity, PushNotificationRegistration.class);
        activity.startService(intent);
    }

    /**
     * Gets the current GCM registration ID from the app's shared preferences
     * If result is empty, the app needs to register with GCM.
     *
     * @return registration ID, or empty string if there is no existing registration ID.
     */
    public static String getRegistrationId(Context context) {
        // Attempt to get the registration ID from shared preferences
        final SharedPreferences prefs = getGCMPreferences(context);
        String registrationId = prefs.getString(Constants.PROPERTY_REG_ID, "");
        if(registrationId.isEmpty()) {
            Log.d(TAG, "getRegistrationId(): Registration ID not found in shared preferences");
            return "";
        }
        // Check if app was updated; if so, it must clear the registration ID
        // since the existing regID is not guaranteed to work with the new
        // app version.
        int registeredVersion = prefs.getInt(Constants.PROPERTY_APP_VERSION, Integer.MIN_VALUE);
        int currentVersion = getAppVersion(context);
        if(registeredVersion != currentVersion) {
            Log.d(TAG, "getRegistrationId(): App version changed. Empty registration ID will be returned.");
            return "";
        }
        Log.d(TAG, "getRegistrationId(): Returning registration ID " + registrationId);
        return registrationId;
    }

    /**
     * @return Application's {@code SharedPreferences}.
     */
    private static SharedPreferences getGCMPreferences(Context context) {
        // This sample app persists the registration ID in shared preferences
        return context.getSharedPreferences(Constants.SHARED_PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * @return Application's version code from the {@code PackageManager}.
     */
    private static int getAppVersion(Context context) {
        Log.d(TAG, "getAppVersion(): Getting app version");
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        }
        catch(PackageManager.NameNotFoundException e) {
            // should never happen
            throw new RuntimeException("Could not get package name: " + e);
        }
    }

    /**
     * Registers the application with GCM servers asynchronously. Stores the
     * registration ID and app versionCode in the application's shared
     * preferences.
     * <p/>
     * Note that because the GCM methods register() and
     * unregister() are blocking, this has to take place on a background thread
     *
     * @param context Application context
     */
    private static void registerInBackground(final Context context) {
        new AsyncTask<Void, Void, String>() {
            private final int MAX_RETRIES = 5;

            @Override protected String doInBackground(Void... params) {
                Log.d(TAG,
                      "registerInBackground(): Registering with GCM in the background using exponential back down");
                boolean retry = false;
                int retries = 0;
                String msg = "";

                do {
                    // Wait before trying GCM registration. This
                    // allows the mobile app to use exponential back down during retries.
                    // See http://developer.android.com/google/gcm/adv.html#retry
                    long waitTime = ((long) Math.pow(2, retries) * 100L);
                    Log.d(TAG, "registerInBackground(): GCM registration wait time = " + waitTime + "ms");
                    try {
                        Thread.sleep(waitTime);
                    }
                    catch(InterruptedException ex) {
                        Log.d(TAG, "registerInBackground(): Ignoring InterruptedException during GCM registration");
                    }

                    try {
                        if(gcm == null) {
                            gcm = GoogleCloudMessaging.getInstance(context);
                        }

                        // Sender ID below comes from Google Developers Console when you
                        // created the Google API project
                        String regid = gcm.register(Constants.SENDER_ID);
                        msg = "registerInBackground(): Device registered, registration ID=" + regid;

                        // Persist the regID - no need to register again.
                        storeRegistrationId(context, regid);

                        // TODO: Typically, the registration ID will be  
                        //       sent to your server here
                    }
                    catch(IOException ex) {
                        msg = ex.getMessage();
                        retry = true;
                    }
                }
                while(retry && retries++ < MAX_RETRIES);
                return msg;
            }

            @Override protected void onPostExecute(String msg) {
                // TODO: Production apps should do a better job of 
                //       handling service not available error
                if(msg.equalsIgnoreCase("SERVICE_NOT_AVAILABLE")) {
                    Log.e(TAG, "registerInBackground(): Unable to register due to SERVICE_NOT_AVAILABLE error");
                }
                else {
                    // Log registration result
                    Log.d(TAG, "registerInBackground(): " + msg);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Stores the registration ID and app versionCode in the application's
     * {@code SharedPreferences}.
     *
     * @param context application's context.
     * @param regId   registration ID
     */
    private static void storeRegistrationId(Context context, String regId) {
        final SharedPreferences prefs = getGCMPreferences(context);
        int appVersion = getAppVersion(context);
        Log.d(TAG, "storeRegistrationId(): Saving GCM registration ID " + regId + " with app version " + appVersion);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(Constants.PROPERTY_REG_ID, regId);
        editor.putInt(Constants.PROPERTY_APP_VERSION, appVersion);
        editor.apply();
    }
}
