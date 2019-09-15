/*
 * Copyright (C) 2018-2019 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.platform.internal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
import android.text.TextUtils;

import lineageos.app.LineageContextConstants;
import lineageos.providers.LineageSettings;
import lineageos.trust.ITrustInterface;
import lineageos.trust.TrustInterface;

import vendor.lineage.trust.V1_0.IUsbRestrict;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.NoSuchElementException;

/** @hide **/
public class TrustInterfaceService extends LineageSystemService {
    private static final String TAG = "LineageTrustInterfaceService";

    private static final String PLATFORM_SECURITY_PATCHES = "ro.build.version.security_patch";
    private static final String VENDOR_SECURITY_PATCHES = "ro.vendor.build.security_patch";
    private static final String LINEAGE_VENDOR_SECURITY_PATCHES =
            "ro.lineage.build.vendor_security_patch";

    private static final String INTENT_PARTS = "org.lineageos.lineageparts.TRUST_INTERFACE";
    private static final String INTENT_ONBOARDING = "org.lineageos.lineageparts.TRUST_HINT";

    private static final String CHANNEL_NAME = "TrustInterface";
    private static final int ONBOARDING_NOTIFCATION_ID = 89;

    private Context mContext;
    private NotificationManager mNotificationManager = null;

    private IUsbRestrict mUsbRestrictor = null;

    public TrustInterfaceService(Context context) {
        super(context);
        mContext = context;
        if (context.getPackageManager().hasSystemFeature(LineageContextConstants.Features.TRUST)) {
            publishBinderService(LineageContextConstants.LINEAGE_TRUST_INTERFACE, mService);
        } else {
            Log.wtf(TAG, "Lineage Trust service started by system server but feature xml not" +
                    " declared. Not publishing binder service!");
        }
    }

    @Override
    public String getFeatureDeclaration() {
        return LineageContextConstants.Features.TRUST;
    }

    @Override
    public void onStart() {
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        try {
            mUsbRestrictor = IUsbRestrict.getService();
        } catch (NoSuchElementException | RemoteException e) {
            // ignore, the hal is not available
        }

        // Onboard
        if (!hasOnboardedUser()) {
            postOnBoardingNotification();
            registerLocaleChangedReceiver();
            return;
        }

        runTestInternal();
    }

    /* Public methods implementation */

    private boolean postNotificationForFeatureInternal(int feature) {
        if (!hasOnboardedUser() || !isWarningAllowed(feature)) {
            return false;
        }

        Pair<Integer, Integer> strings = getNotificationStringsForFeature(feature);
        if (strings == null) {
            return false;
        }

        String title = mContext.getString(strings.first);
        String message = mContext.getString(strings.second);
        String action = mContext.getString(R.string.trust_notification_action_manage);

        Intent mainIntent = new Intent(INTENT_PARTS);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pMainIntent = PendingIntent.getActivity(mContext, 0, mainIntent, 0);

        Intent actionIntent = new Intent(INTENT_PARTS);
        actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        actionIntent.putExtra(":settings:fragment_args_key", "trust_category_alerts");
        PendingIntent pActionIntent = PendingIntent.getActivity(mContext, 0, actionIntent, 0);

        Notification.Builder notification = new Notification.Builder(mContext, CHANNEL_NAME)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(pMainIntent)
                .addAction(R.drawable.ic_trust_notification_manage, action, pActionIntent)
                .setColor(mContext.getColor(R.color.color_error))
                .setSmallIcon(R.drawable.ic_warning);

        createNotificationChannelIfNeeded();
        mNotificationManager.notify(feature, notification.build());
        return true;
    }

    private boolean removeNotificationForFeatureInternal(int feature) {
        if (!isWarningAllowed(feature)) {
            return false;
        }

        mNotificationManager.cancel(feature);
        return true;
    }

    private boolean hasUsbRestrictorInternal() {
        return mUsbRestrictor != null;
    }

    private boolean postOnBoardingNotification() {
        String title = mContext.getString(R.string.trust_notification_title_onboarding);
        String message = mContext.getString(R.string.trust_notification_content_onboarding);
        Intent intent = new Intent(INTENT_ONBOARDING);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pIntent = PendingIntent.getActivity(mContext, 0, intent, 0);

        Notification.Builder notification = new Notification.Builder(mContext, CHANNEL_NAME)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(pIntent)
                .setSmallIcon(R.drawable.ic_trust);

        createNotificationChannelIfNeeded();
        mNotificationManager.notify(ONBOARDING_NOTIFCATION_ID, notification.build());
        return true;
    }

    private int getLevelForFeatureInternal(int feature) {
        switch (feature) {
            case TrustInterface.TRUST_FEATURE_SELINUX:
                return getSELinuxStatus();
            case TrustInterface.TRUST_FEATURE_ROOT:
                return getRootStatus();
            case TrustInterface.TRUST_FEATURE_PLATFORM_SECURITY_PATCH:
                return getSecurityPatchStatus(PLATFORM_SECURITY_PATCHES);
            case TrustInterface.TRUST_FEATURE_VENDOR_SECURITY_PATCH:
                return getSecurityPatchStatus(VENDOR_SECURITY_PATCHES);
            case TrustInterface.TRUST_FEATURE_ENCRYPTION:
                return getEncryptionStatus();
            case TrustInterface.TRUST_FEATURE_KEYS:
                return getKeysStatus();
            default:
                return TrustInterface.ERROR_UNDEFINED;
        }
    }

    private void runTestInternal() {
        int selinuxStatus = getSELinuxStatus();
        if (selinuxStatus != TrustInterface.TRUST_FEATURE_LEVEL_GOOD) {
            postNotificationForFeatureInternal(TrustInterface.TRUST_WARN_SELINUX);
        }

        int keysStatus = getKeysStatus();
        if (keysStatus != TrustInterface.TRUST_FEATURE_LEVEL_GOOD) {
            postNotificationForFeatureInternal(TrustInterface.TRUST_WARN_PUBLIC_KEY);
        }
    }

    /* Utils */

    private void enforceTrustPermission() {
        mContext.enforceCallingOrSelfPermission(TrustInterface.TRUST_INTERFACE_PERMISSION,
                "You do not have permissions to use the Trust interface");
    }

    private boolean isWarningAllowed(int warning) {
        return (LineageSettings.Secure.getInt(mContext.getContentResolver(),
                LineageSettings.Secure.TRUST_WARNINGS,
                TrustInterface.TRUST_WARN_MAX_VALUE) & warning) != 0;
    }

    private Pair<Integer, Integer> getNotificationStringsForFeature(int feature) {
        int title = 0;
        int message = 0;

        switch (feature) {
            case TrustInterface.TRUST_WARN_SELINUX:
                title = R.string.trust_notification_title_security;
                message = R.string.trust_notification_content_selinux;
                break;
            case TrustInterface.TRUST_WARN_ROOT:
                title = R.string.trust_notification_title_root;
                message = R.string.trust_notification_content_root;
                break;
            case TrustInterface.TRUST_WARN_PUBLIC_KEY:
                title = R.string.trust_notification_title_security;
                message = R.string.trust_notification_content_keys;
                break;
        }

        return title == 0 ? null : new Pair(title, message);
    }

    private void createNotificationChannelIfNeeded() {
        NotificationChannel channel = mNotificationManager.getNotificationChannel(CHANNEL_NAME);
        if (channel != null) {
            return;
        }

        String name = mContext.getString(R.string.trust_notification_channel);
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel trustChannel = new NotificationChannel(CHANNEL_NAME,
                name, importance);
        trustChannel.setBlockableSystem(true);
        mNotificationManager.createNotificationChannel(trustChannel);
    }

    private int getSELinuxStatus() {
        return SELinux.isSELinuxEnforced() ?
                TrustInterface.TRUST_FEATURE_LEVEL_GOOD :
                TrustInterface.TRUST_FEATURE_LEVEL_BAD;
    }

    private int getRootStatus() {
        String status = SystemProperties.get("persist.sys.root_access", "0");
        switch (status) {
            case "0":
                return TrustInterface.TRUST_FEATURE_LEVEL_GOOD;
            case "1":
            case "3":
                return TrustInterface.TRUST_FEATURE_LEVEL_BAD;
            case "2":
                return TrustInterface.TRUST_FEATURE_LEVEL_POOR;
            default:
                return TrustInterface.ERROR_UNDEFINED;
        }
    }

    private int getSecurityPatchStatus(String target) {
        String patchLevel = SystemProperties.get(target);
        if (TextUtils.isEmpty(patchLevel)) {
            // Try to fallback to Lineage vendor prop
            if (VENDOR_SECURITY_PATCHES.equals(target)) {
                    patchLevel = SystemProperties.get(LINEAGE_VENDOR_SECURITY_PATCHES);
                    if (TextUtils.isEmpty(patchLevel)) {
                        return TrustInterface.ERROR_UNDEFINED;
                    }
            } else {
                return TrustInterface.ERROR_UNDEFINED;
            }
        }

        Calendar today = Calendar.getInstance();
        Calendar patchCal = Calendar.getInstance();

        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd").parse(patchLevel);
            patchCal.setTime(date);

            int diff = (today.get(Calendar.YEAR) - patchCal.get(Calendar.YEAR)) * 12 +
                    today.get(Calendar.MONTH) - patchCal.get(Calendar.MONTH);
            if (diff < 0) {
                // This is a blatant lie
                return TrustInterface.TRUST_FEATURE_LEVEL_BAD;
            } else if (diff < 6) {
                return TrustInterface.TRUST_FEATURE_LEVEL_GOOD;
            } else if (diff < 12) {
                return TrustInterface.TRUST_FEATURE_LEVEL_POOR;
            }
            return TrustInterface.TRUST_FEATURE_LEVEL_BAD;
        } catch (ParseException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return TrustInterface.ERROR_UNDEFINED;
    }

    private int getEncryptionStatus() {
        DevicePolicyManager policyManager = mContext.getSystemService(DevicePolicyManager.class);
        if (policyManager == null) {
            return TrustInterface.ERROR_UNDEFINED;
        }

        int status = policyManager.getStorageEncryptionStatus();

        switch (status) {
            case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE:
            case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER:
                return TrustInterface.TRUST_FEATURE_LEVEL_GOOD;
            case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY:
                return TrustInterface.TRUST_FEATURE_LEVEL_POOR;
            case DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE:
            case DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED:
                return TrustInterface.TRUST_FEATURE_LEVEL_BAD;
            default:
                return TrustInterface.ERROR_UNDEFINED;
        }
    }

    private int getKeysStatus() {
        String buildTags = SystemProperties.get("ro.build.tags");

        if (buildTags.contains("test-keys")) {
            return TrustInterface.TRUST_FEATURE_LEVEL_BAD;
        } else if (buildTags.contains("release-keys") || buildTags.contains("dev-keys")) {
            return TrustInterface.TRUST_FEATURE_LEVEL_GOOD;
        }
        return TrustInterface.ERROR_UNDEFINED;
    }

    private boolean hasOnboardedUser() {
        return LineageSettings.System.getInt(mContext.getContentResolver(),
                LineageSettings.System.TRUST_INTERFACE_HINTED, 0) == 1;
    }

    private void registerLocaleChangedReceiver() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_LOCALE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == Intent.ACTION_LOCALE_CHANGED) {
                if (!hasOnboardedUser()) {
                    // When are not onboarded, we want to change the language of the notification
                    postOnBoardingNotification();
                } else {
                    // We don't care anymore about language changes
                    context.unregisterReceiver(this);
                }
            }
        }
    };

    /* Service */

    private final IBinder mService = new ITrustInterface.Stub() {
        @Override
        public boolean postNotificationForFeature(int feature) {
            enforceTrustPermission();
            /*
             * We need to clear the caller's identity in order to
             *   allow this method call to modify settings
             *   not allowed by the caller's permissions.
             */
            long token = clearCallingIdentity();
            boolean success = postNotificationForFeatureInternal(feature);
            restoreCallingIdentity(token);
            return success;
        }

        @Override
        public boolean removeNotificationForFeature(int feature) {
            enforceTrustPermission();
            /*
             * We need to clear the caller's identity in order to
             *   allow this method call to modify settings
             *   not allowed by the caller's permissions.
             */
            long token = clearCallingIdentity();
            boolean success = removeNotificationForFeatureInternal(feature);
            restoreCallingIdentity(token);
            return success;
        }

        @Override
        public boolean hasUsbRestrictor() {
            /*
             * No need to require permission for this one because it's harmless
             */
             return hasUsbRestrictorInternal();
        }

        @Override
        public int getLevelForFeature(int feature) {
            /*
             * No need to require permission for this one because it's harmless
             */
            return getLevelForFeatureInternal(feature);
        }

        @Override
        public void runTest() {
            enforceTrustPermission();
            /*
             * We need to clear the caller's identity in order to
             *   allow this method call to modify settings
             *   not allowed by the caller's permissions.
             */
            long token = clearCallingIdentity();
            runTestInternal();
            restoreCallingIdentity(token);
        }
    };
}
