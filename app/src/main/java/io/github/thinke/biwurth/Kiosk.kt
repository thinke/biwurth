package io.github.thinke.biwurth

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class AdminReceiver : DeviceAdminReceiver()

/**
 * Kiosk mode for a wall panel. Device admin (set over adb) is enough for "Screen off" sleep;
 * device owner (README) adds no lock screen, always-home, lock task mode, auto-granted
 * permissions and silent updates.
 */
object Kiosk {
    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)
    private fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    fun isAdmin(context: Context) = dpm(context).isAdminActive(admin(context))
    fun isDeviceOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

    fun isLocked(activity: Activity) =
        activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    fun applyPolicies(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        // No status bar, notifications, Home or Recents while locked.
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        dpm.setKeyguardDisabled(admin, true)
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, ComponentName(context, MainActivity::class.java))
        // Every runtime permission biwurth asks for is granted without a prompt; nobody is at the panel.
        dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT)
    }

    /**
     * Gives up device owner (and with it kiosk mode and silent updates). adb can't remove a device
     * owner, so without this only a factory reset could; afterwards biwurth can be uninstalled.
     */
    fun releaseDeviceOwner(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        leave(activity)
        val dpm = dpm(activity)
        dpm.clearPackagePersistentPreferredActivities(admin(activity), activity.packageName)
        dpm.setKeyguardDisabled(admin(activity), false)
        dpm.clearDeviceOwnerApp(activity.packageName)
    }

    // Without device owner, startLockTask() would ask the user to pin the app instead.
    fun enter(activity: Activity) {
        if (isDeviceOwner(activity) && !isLocked(activity)) activity.startLockTask()
    }

    fun leave(activity: Activity) {
        if (isLocked(activity)) activity.stopLockTask()
    }
}
