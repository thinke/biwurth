package io.github.thinke.biwurth

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Becoming device admin or owner (the in-app request, adb, or QR provisioning) configures the
 * panel at once, without waiting for a restart.
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = Kiosk.onRightsChanged(context)
    override fun onDisabled(context: Context, intent: Intent) = Kiosk.onRightsChanged(context)
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) = Kiosk.onRightsChanged(context)
}

/**
 * Kiosk mode for a wall panel. Device admin (set over adb) is enough for "Screen off" sleep;
 * device owner (README) adds no lock screen, always-home, lock task mode, auto-granted
 * permissions and silent updates.
 */
object Kiosk {
    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)
    private fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    /** Whether biwurth is device owner; MainActivity follows it to enter kiosk mode at once. */
    val deviceOwner = MutableStateFlow(false)

    fun isAdmin(context: Context) = dpm(context).isAdminActive(admin(context))

    fun onRightsChanged(context: Context) {
        applyPolicies(context)
        deviceOwner.value = isDeviceOwner(context)
    }

    /**
     * Android's own "Activate device admin?" dialog on the tablet (also from the website):
     * enough for the "Screen off" sleep mode.
     */
    fun requestAdmin(context: Context) {
        context.startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(context))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets biwurth switch the screen off when it sleeps.")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Drops device admin, so biwurth can be uninstalled; adb can't remove an admin that isn't a test app. */
    fun removeAdmin(context: Context) {
        if (isAdmin(context) && !isDeviceOwner(context)) dpm(context).removeActiveAdmin(admin(context))
    }
    fun isDeviceOwner(context: Context) = dpm(context).isDeviceOwnerApp(context.packageName)

    fun isLocked(activity: Activity) =
        activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    fun applyPolicies(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        // No status bar, notifications, Home or Recents while locked, only the power menu; before
        // Android 9 that's already how lock task behaves and there are no features to set.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin, KIOSK_FEATURES)
        }
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
        deviceOwner.value = false
    }

    /**
     * Opens Android's Wi-Fi panel or Settings from the kiosk: the Settings app may run in lock task
     * mode until biwurth is back on screen ([closeSystemSettings]), with Home enabled to get back.
     */
    fun openSystemSettings(activity: Activity, wifiOnly: Boolean) {
        if (isDeviceOwner(activity)) {
            val dpm = dpm(activity)
            val admin = admin(activity)
            dpm.setLockTaskPackages(admin, arrayOf(activity.packageName, SETTINGS_PACKAGE))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, KIOSK_FEATURES or DevicePolicyManager.LOCK_TASK_FEATURE_HOME)
            } else {
                // Home can't be enabled in lock task before Android 9, so unlock instead of leaving
                // Back as the only way out; onResume locks again when biwurth is back.
                leave(activity)
            }
        }
        val wifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
        val intent = Intent(if (wifiOnly) wifi else Settings.ACTION_SETTINGS)
        // In biwurth's task (root if_whitelisted) Android 8 locks Settings again at once; its own task stays unlocked.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
    }

    /** Back in biwurth: only biwurth may run in kiosk mode again, without Home. */
    fun closeSystemSettings(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        dpm.setLockTaskPackages(admin(context), arrayOf(context.packageName))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(admin(context), KIOSK_FEATURES)
        }
    }

    private const val SETTINGS_PACKAGE = "com.android.settings"

    // Locked, but holding the power button still opens the power menu (power off, restart):
    // without it a kiosk tablet can't be switched off at all.
    private const val KIOSK_FEATURES = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS

    // Without device owner, startLockTask() would ask the user to pin the app instead.
    fun enter(activity: Activity) {
        if (isDeviceOwner(activity) && !isLocked(activity)) activity.startLockTask()
    }

    fun leave(activity: Activity) {
        if (isLocked(activity)) activity.stopLockTask()
    }
}
