package com.android.managedprovisioning.task;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.managedprovisioning.ProvisionLogger;
import com.android.managedprovisioning.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Removes all apps that are not required for a managed profile to work. This includes UI
 * components such as the launcher.
 */
public class DeleteNonRequiredAppsFromManagedProfileTask {
    private final Callback mCallback;
    private final Context mContext;
    private final IPackageManager mIpm;
    private final PackageManager mPm;
    private final UserInfo mManagedProfileUserInfo;
    private final String mMdmPackageName;

    public DeleteNonRequiredAppsFromManagedProfileTask(Context context,
            String mdmPackageName, UserInfo managedProfileUserInfo, Callback callback) {
        mCallback = callback;
        mContext = context;
        mMdmPackageName = mdmPackageName;
        mManagedProfileUserInfo = managedProfileUserInfo;
        mIpm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        mPm = context.getPackageManager();
    }

    public void run() {
        ProvisionLogger.logd("Deleting non required apps from managed profile.");

        List<PackageInfo> allPackages = null;
        try {
            allPackages = mIpm.getInstalledPackages(PackageManager.GET_SIGNATURES,
                        mManagedProfileUserInfo.id).getList();
        } catch (RemoteException neverThrown) {
            // Never thrown, as we are making local calls.
            ProvisionLogger.loge("This should not happen.", neverThrown);
        }

        HashSet<String> requiredApps = new HashSet<String> (Arrays.asList(
                mContext.getResources().getStringArray(R.array.required_managedprofile_apps)));
        requiredApps.add(mMdmPackageName);
        requiredApps.addAll(getImePackages());
        requiredApps.addAll(getAccessibilityPackages());
        requiredApps.addAll(Arrays.asList(mContext.getResources().getStringArray(
                R.array.vendor_required_managedprofile_apps)));

        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launcherActivities = mPm.queryIntentActivitiesAsUser(
                launcherIntent, 0 /* no flags */, mManagedProfileUserInfo.id);

        Set<String> packagesToDelete = new HashSet<String>();
        for (ResolveInfo activity : launcherActivities) {
            String packageName = activity.activityInfo.packageName;
            if (!requiredApps.contains(packageName)) {
                packagesToDelete.add(packageName);
            }
        }

        for (String packageName : packagesToDelete) {
            try {
                mIpm.setApplicationBlockedSettingAsUser(packageName, true,
                        mManagedProfileUserInfo.id);
            } catch (RemoteException neverThrown) {
                // Never thrown, as we are making local calls.
                ProvisionLogger.loge("This should not happen.", neverThrown);
                mCallback.onError();
            }
        }
        mCallback.onSuccess();
    }

    private List<String> getImePackages() {
        ArrayList<String> imePackages = new ArrayList<String>();
        InputMethodManager imm =
                (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imis = imm.getInputMethodList();
        for (InputMethodInfo imi : imis) {
            if (isSystemPackage(imi.getPackageName())) {
                imePackages.add(imi.getPackageName());
            }
        }
        return imePackages;
    }

    private boolean isSystemPackage(String packageName) {
        try {
            final PackageInfo pi = mIpm.getPackageInfo(packageName, 0, mManagedProfileUserInfo.id);
            if (pi == null || pi.applicationInfo == null) return false;
            final int flags = pi.applicationInfo.flags;
            if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                return true;
            }
        } catch (RemoteException neverThrown) {
            // Never thrown, as we are making local calls.
            ProvisionLogger.loge("This should not happen.", neverThrown);
        }
        return false;
    }

    private List<String> getAccessibilityPackages() {
        ArrayList<String> accessibilityPackages = new ArrayList<String>();
        AccessibilityManager am =
                (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> asis = am.getInstalledAccessibilityServiceList();
        for (AccessibilityServiceInfo asi : asis) {
            String packageName = asi.getResolveInfo().serviceInfo.packageName;
            if (isSystemPackage(packageName)) {
                accessibilityPackages.add(packageName);
            }
        }
        return accessibilityPackages;
    }

    public abstract static class Callback {
        public abstract void onSuccess();
        public abstract void onError();
    }
}
