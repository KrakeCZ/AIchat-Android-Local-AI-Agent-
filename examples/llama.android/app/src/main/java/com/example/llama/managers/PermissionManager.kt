package com.example.llama.managers

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Řeší systémová oprávnění (Notifikace, Mikrofon, atd.). Odděluje systémový boilerplate od logiky
 * Activity.
 */
class PermissionManager(private val activity: AppCompatActivity) {

    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var audioPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var agentPermissionsLauncher: ActivityResultLauncher<Array<String>>

    // Callback pro výsledek žádosti o audio a kameru
    var onAudioPermissionResult: ((Boolean) -> Unit)? = null
    var onCameraPermissionResult: ((Boolean) -> Unit)? = null
    var onAgentPermissionsResult: ((Boolean) -> Unit)? = null

    init {
        registerLaunchers()
    }

    private fun registerLaunchers() {
        // Registrace callbacku musí proběhnout při inicializaci (v onCreate)
        notificationPermissionLauncher =
                activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                        isGranted: Boolean ->
                    if (isGranted) {
                        Log.i("PermissionManager", "Notification permission granted.")
                    } else {
                        Log.w("PermissionManager", "Notification permission denied.")
                        Toast.makeText(
                                        activity,
                                        "Notifikační oprávnění zamítnuto - generování na pozadí nebude viditelné.",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                    }
                }

        audioPermissionLauncher =
                activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                        isGranted: Boolean ->
                    if (isGranted) {
                        Log.i("PermissionManager", "Audio permission granted.")
                    } else {
                        Log.w("PermissionManager", "Audio permission denied.")
                        Toast.makeText(
                                        activity,
                                        "Oprávnění nahrávat zvuk zamítnuto - hlasové ovládání nebude fungovat.",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                    }
                    onAudioPermissionResult?.invoke(isGranted)
                }

        cameraPermissionLauncher =
                activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                        isGranted: Boolean ->
                    if (isGranted) {
                        Log.i("PermissionManager", "Camera permission granted.")
                    } else {
                        Log.w("PermissionManager", "Camera permission denied.")
                        Toast.makeText(
                                        activity,
                                        "Oprávnění kamery zamítnuto - sledování očí (IrisMotion) nebude fungovat.",
                                        Toast.LENGTH_LONG
                                )
                                .show()
                    }
                    onCameraPermissionResult?.invoke(isGranted)
                }

        // Multi-permission launcher pro Agent Core (Call, SMS, Contacts...)
        agentPermissionsLauncher =
                activity.registerForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val allGranted = permissions.entries.all { it.value }
                    if (allGranted) {
                        Log.i("PermissionManager", "All agent permissions granted.")
                        onAgentPermissionsResult?.invoke(true)
                    } else {
                        val denied = permissions.entries.filter { !it.value }.map { it.key }
                        Log.w("PermissionManager", "Agent permissions denied: $denied")

                        // Check if any denied permission is permanently denied (shouldShow...
                        // returns false)
                        // Note: logic is inverted. If shouldShow... is TRUE, it's NOT permanent (we
                        // can explain).
                        // If FALSE, it's either not requested yet (impossible here) OR permanent.
                        val permanentlyDenied =
                                denied.any { !activity.shouldShowRequestPermissionRationale(it) }

                        if (permanentlyDenied) {
                            showSettingsDialog(denied)
                        } else {
                            showRationaleDialog(denied) {
                                agentPermissionsLauncher.launch(denied.toTypedArray())
                            }
                        }

                        onAgentPermissionsResult?.invoke(false)
                    }
                }
    }

    /**
     * Bezpečně zkontroluje a případně požádá o oprávnění pro notifikace (Android 13+). Na starších
     * Androidech nedělá nic (oprávnění je uděleno automaticky).
     */
    fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus =
                    ContextCompat.checkSelfPermission(
                            activity,
                            Manifest.permission.POST_NOTIFICATIONS
                    )

            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                // Můžeme zde přidat logiku "shouldShowRequestPermissionRationale" pro vysvětlení
                // uživateli
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** Zkontroluje a požádá o oprávnění RECORD_AUDIO */
    fun checkAndRequestAudioPermission(onResult: ((Boolean) -> Unit)? = null) {
        this.onAudioPermissionResult = onResult
        val permissionStatus =
                ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)

        if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
            onResult?.invoke(true)
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Synchronous check for Audio Permission */
    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** Zkontroluje a požádá o oprávnění CAMERA */
    fun checkAndRequestCameraPermission(onResult: ((Boolean) -> Unit)? = null) {
        this.onCameraPermissionResult = onResult
        val permissionStatus =
                ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)

        if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
            onResult?.invoke(true)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Synchronous check for Camera Permission */
    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    // === Agent Core Permissions ===

    /**
     * Seznam oprávnění nezbytných pro plnou funkčnost Agenta. CALL_PHONE, READ_CALL_LOG, SEND_SMS
     * odstraněny — Google Play Compliance.
     */
    private val REQUIRED_AGENT_PERMISSIONS =
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)

    /**
     * Zkontroluje a případně vyžádá všechna potřebná Runtime oprávnění pro Agenta. (Audio,
     * Contacts)
     */
    fun checkAndRequestAgentPermissions(onResult: ((Boolean) -> Unit)? = null) {
        this.onAgentPermissionsResult = onResult

        val missingPermissions =
                REQUIRED_AGENT_PERMISSIONS
                        .filter {
                            ContextCompat.checkSelfPermission(activity, it) !=
                                    PackageManager.PERMISSION_GRANTED
                        }
                        .toTypedArray()

        if (missingPermissions.isEmpty()) {
            onResult?.invoke(true)
        } else {
            Log.i(
                    "PermissionManager",
                    "Requesting missing permissions: ${missingPermissions.joinToString()}"
            )
            agentPermissionsLauncher.launch(missingPermissions)
        }
    }

    /**
     * Zkontroluje, zda má aplikace speciální oprávnění WRITE_SETTINGS (System Control -
     * Jas/Rotace).
     */
    fun hasWriteSettingsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.System.canWrite(activity)
        } else {
            true // Před Android 6.0 bylo uděleno v Manifestu
        }
    }

    /** Otevře systémové nastavení pro udělení WRITE_SETTINGS. */
    fun requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent =
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                            .apply {
                                data = android.net.Uri.parse("package:${activity.packageName}")
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            }
            try {
                activity.startActivity(intent)
                Toast.makeText(
                                activity,
                                "Prosím povolte úpravu nastavení systému.",
                                Toast.LENGTH_LONG
                        )
                        .show()
            } catch (e: Exception) {
                Log.e("PermissionManager", "Failed to open WRITE_SETTINGS", e)
                Toast.makeText(activity, "Nelze otevřít nastavení oprávnění.", Toast.LENGTH_SHORT)
                        .show()
            }
        }
    }

    private fun showSettingsDialog(deniedPermissions: List<String>) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Chybí oprávnění")
                .setMessage(
                        "Některá oprávnění (Audio, Kontakty) byla trvale zamítnuta. Agent bez nich nemůže plně fungovat.\n\nProsím povolte je manuálně v Nastavení aplikace."
                )
                .setPositiveButton("Otevřít Nastavení") { _, _ ->
                    val intent =
                            android.content.Intent(
                                            android.provider.Settings
                                                    .ACTION_APPLICATION_DETAILS_SETTINGS
                                    )
                                    .apply {
                                        data =
                                                android.net.Uri.fromParts(
                                                        "package",
                                                        activity.packageName,
                                                        null
                                                )
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                    try {
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("PermissionManager", "Failed to open app settings", e)
                    }
                }
                .setNegativeButton("Zrušit") { dialog, _ -> dialog.dismiss() }
                .show()
    }

    private fun showRationaleDialog(deniedPermissions: List<String>, onRetry: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Potřebná oprávnění")
                .setMessage(
                        "Agent potřebuje přístup k mikrofonu a kontaktům pro hlasové ovládání.\n\nProsím povolte tato oprávnění v dalším kroku."
                )
                .setPositiveButton("Zkusit znovu") { _, _ -> onRetry() }
                .setNegativeButton("Zrušit") { dialog, _ -> dialog.dismiss() }
                .show()
    }
}
