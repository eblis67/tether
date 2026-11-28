package app.tether.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.tether.R
import app.tether.TetherApp
import app.tether.adb.Adb

/**
 * The per-brand setup guide (docs/SETUP-WIZARD.md): which steps this phone needs, which of them the app can
 * check itself, and the best settings screen for the ones it cannot. The catalogue and the checks live here;
 * [MainActivity] only draws them.
 *
 * Two kinds of steps. Verified steps read real state (permission grants, the dialer role, the battery
 * exemption, the helper's own status) and re-check on every render — coming back from a settings screen is
 * enough. Confirm steps are the maker settings no app may read (MIUI/ColorOS/Flyme auto-start flags, the
 * makers' own battery savers, Samsung's sleeping-apps list, the Recents lock): the user changes them in the
 * maker's screens and taps "I did it", and the confirmation is kept in prefs so the guide never nags twice.
 */
object SetupWizard {
    const val ID_PERMISSIONS = "permissions"
    const val ID_DIALER = "dialer"
    const val ID_BATTERY = "battery"
    const val ID_AUTOSTART = "autostart"
    const val ID_BRAND_BATTERY = "brand_battery"
    const val ID_NEVER_SLEEPING = "never_sleeping"
    const val ID_LOCK_RECENTS = "lock_recents"
    const val ID_ADB = "adb"
    const val ID_HELPER = "helper"

    enum class Brand { SAMSUNG, XIAOMI, COLOROS, MEIZU, GENERIC }

    /** One wizard step: string resources for its title and explanation, whether the user confirms it by hand,
     *  and the action button's label (0 = no settings screen exists for this step, e.g. the Recents lock). */
    data class StepDef(val id: String, val titleRes: Int, val helpRes: Int, val confirm: Boolean, val actionRes: Int)

    /** The maker behind this phone, from the build fingerprint. Redmi and POCO are Xiaomi brands (MIUI /
     *  HyperOS); Realme and OnePlus ship ColorOS under their own names, so they share OPPO's steps. */
    fun brand(manufacturer: String?, brand: String?): Brand {
        val m = (manufacturer ?: "").lowercase()
        val b = (brand ?: "").lowercase()
        return when {
            m.contains("samsung") || b.contains("samsung") -> Brand.SAMSUNG
            m.contains("xiaomi") || m.contains("redmi") || b.contains("redmi") || b.contains("poco") -> Brand.XIAOMI
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ||
                b.contains("oppo") || b.contains("realme") || b.contains("oneplus") -> Brand.COLOROS
            m.contains("meizu") || b.contains("meizu") -> Brand.MEIZU
            else -> Brand.GENERIC
        }
    }

    fun detectBrand(): Brand = brand(Build.MANUFACTURER, Build.BRAND)

    fun brandNameRes(brand: Brand): Int = when (brand) {
        Brand.SAMSUNG -> R.string.brand_samsung
        Brand.XIAOMI -> R.string.brand_xiaomi
        Brand.COLOROS -> R.string.brand_coloros
        Brand.MEIZU -> R.string.brand_meizu
        Brand.GENERIC -> R.string.brand_generic
    }

    /** Runtime permissions this Android version knows: Bluetooth ones from 12, notifications from 13.
     *  The single source for the list: MainActivity requests exactly these, the wizard checks exactly these. */
    fun requiredPermissions(sdk: Int): Array<String> = (
        (if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) else emptyList()) +
        (if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()) + listOf(
        Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )).toTypedArray()

    /** The ordered steps for a brand: the universal ones (same checks as the Setup card), the maker's own
     *  survival steps, then Wireless debugging and the helper. Generic Android needs no maker steps: Doze is
     *  its only background killer, and the verified battery step already covers it. */
    fun steps(brand: Brand, sdk: Int): List<StepDef> {
        val out = ArrayList<StepDef>()
        out += StepDef(ID_PERMISSIONS, R.string.step_permissions, R.string.help_permissions, confirm = false, actionRes = R.string.action_grant)
        out += StepDef(ID_DIALER, R.string.step_dialer, R.string.help_dialer, confirm = false, actionRes = R.string.action_set)
        out += StepDef(ID_BATTERY, R.string.step_battery, R.string.help_battery, confirm = false, actionRes = R.string.action_set)
        when (brand) {
            Brand.SAMSUNG -> {
                out += StepDef(ID_NEVER_SLEEPING, R.string.step_never_sleeping, R.string.help_never_sleeping, confirm = true, actionRes = R.string.wizard_open_settings)
                out += StepDef(ID_LOCK_RECENTS, R.string.step_lock_recents, R.string.help_lock_recents, confirm = true, actionRes = 0)
            }
            Brand.XIAOMI -> {
                out += StepDef(ID_AUTOSTART, R.string.step_autostart, R.string.help_autostart_xiaomi, confirm = true, actionRes = R.string.wizard_open_settings)
                out += StepDef(ID_BRAND_BATTERY, R.string.step_brand_battery, R.string.help_brand_battery_xiaomi, confirm = true, actionRes = R.string.wizard_open_settings)
                out += StepDef(ID_LOCK_RECENTS, R.string.step_lock_recents, R.string.help_lock_recents, confirm = true, actionRes = 0)
            }
            Brand.COLOROS -> {
                out += StepDef(ID_AUTOSTART, R.string.step_autostart, R.string.help_autostart_coloros, confirm = true, actionRes = R.string.wizard_open_settings)
                out += StepDef(ID_BRAND_BATTERY, R.string.step_brand_battery, R.string.help_brand_battery_coloros, confirm = true, actionRes = R.string.wizard_open_settings)
                out += StepDef(ID_LOCK_RECENTS, R.string.step_lock_recents, R.string.help_lock_recents, confirm = true, actionRes = 0)
            }
            Brand.MEIZU -> {
                out += StepDef(ID_AUTOSTART, R.string.step_autostart, R.string.help_autostart_meizu, confirm = true, actionRes = R.string.wizard_open_settings)
                out += StepDef(ID_BRAND_BATTERY, R.string.step_brand_battery, R.string.help_brand_battery_meizu, confirm = true, actionRes = R.string.wizard_open_settings)
                out += StepDef(ID_LOCK_RECENTS, R.string.step_lock_recents, R.string.help_lock_recents, confirm = true, actionRes = 0)
            }
            Brand.GENERIC -> {}
        }
        // Wireless debugging exists from Android 11; before that only TCP adb (Setup card) can start the helper.
        if (sdk >= 30) out += StepDef(ID_ADB, R.string.step_adb, R.string.help_adb, confirm = false, actionRes = R.string.action_pair)
        out += StepDef(ID_HELPER, R.string.step_helper, R.string.help_helper, confirm = false, actionRes = R.string.action_start)
        return out
    }

    /** Real state for the verified steps — the same checks the Setup and Call audio cards already show.
     *  Confirm steps have no readable state and always report false here; [isDone] answers those from prefs. */
    fun isVerified(id: String, ctx: Context, app: TetherApp): Boolean = when (id) {
        ID_PERMISSIONS -> requiredPermissions(Build.VERSION.SDK_INT)
            .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        ID_DIALER -> app.sims.isDialer()
        ID_BATTERY -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        ID_ADB -> Adb.hasSecureSettings(ctx) && app.prefs.getBoolean("adb_paired", false)
        ID_HELPER -> app.hub.audio.helperStatus().optString("state") == "running"
        else -> false
    }

    fun isConfirmed(app: TetherApp, id: String): Boolean = app.prefs.getBoolean("wizard_ok_$id", false)

    fun setConfirmed(app: TetherApp, id: String, done: Boolean) {
        app.prefs.edit().putBoolean("wizard_ok_$id", done).apply()
    }

    fun isDone(def: StepDef, ctx: Context, app: TetherApp): Boolean =
        if (def.confirm) isConfirmed(app, def.id) else isVerified(def.id, ctx, app)

    /** Steps still to do on this phone — drives the Setup card's status row and the first-launch auto-open. */
    fun remaining(ctx: Context, app: TetherApp): Int =
        steps(detectBrand(), Build.VERSION.SDK_INT).count { !isDone(it, ctx, app) }

    /**
     * The best settings screen a step can open, or null when there is none (the Recents lock happens in the
     * Recents screen itself). Maker screens are best-effort: MIUI's auto-start list and ColorOS's startup
     * list exist on most builds but are renamed on some, and Flyme offers no auto-start screen to other apps
     * at all — the caller falls back to app details when starting the intent fails, and every step's help
     * text gives the full manual path either way.
     */
    fun settingsIntent(id: String, brand: Brand, packageName: String): Intent? = when (id) {
        ID_AUTOSTART -> when (brand) {
            Brand.XIAOMI -> Intent().setComponent(ComponentName(
                "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            Brand.COLOROS -> Intent().setComponent(ComponentName(
                "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            else -> appDetailsIntent(packageName)
        }
        ID_BRAND_BATTERY -> appDetailsIntent(packageName)
        // Samsung's Never sleeping apps screen cannot be deep-linked; the system battery list is the
        // closest starting point, and the step's text gives the exact path from Settings.
        ID_NEVER_SLEEPING -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> null
    }

    fun appDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
}
