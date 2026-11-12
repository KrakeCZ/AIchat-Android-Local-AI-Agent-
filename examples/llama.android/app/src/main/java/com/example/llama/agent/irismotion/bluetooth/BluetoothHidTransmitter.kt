package com.example.llama.agent.irismotion.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class BluetoothHidTransmitter(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "BtHidTransmitter"

        const val LEFT_BUTTON = 1
        const val RIGHT_BUTTON = 2
        const val MIDDLE_BUTTON = 4

        private const val ID_MOUSE: Byte = 2
        private const val ID_DIGITIZER: Byte = 3

        // Kompozitní Deskriptor (Combo: Myš + Absolutní Digitizer)
        private val COMBO_REPORT_DESCRIPTOR = byteArrayOf(
            // --- RELATIVE MOUSE (Report ID 2) ---
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x02, // Usage (Mouse)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x85.toByte(), ID_MOUSE,    //    Report ID (2)
            0x09, 0x01, //    Usage (Pointer)
            0xA1.toByte(), 0x00, //    Collection (Physical)
            0x05, 0x09, //       Usage Page (Buttons)
            0x19, 0x01, //       Usage minimum (1)
            0x29, 0x03, //       Usage maximum (3)
            0x15, 0x00, //       Logical minimum (0)
            0x25, 0x01, //       Logical maximum (1)
            0x75, 0x01, //       Report size (1)
            0x95.toByte(), 0x03, //       Report count (3)
            0x81.toByte(), 0x02, //       Input (Data, Variable, Absolute)
            0x75, 0x05, //       Report size (5)
            0x95.toByte(), 0x01, //       Report count (1)
            0x81.toByte(), 0x01, //       Input (constant)                 ; 5 bit padding
            0x05, 0x01, //       Usage page (Generic Desktop)
            0x09, 0x30, //       Usage (X)
            0x09, 0x31, //       Usage (Y)
            0x09, 0x38, //       Usage (Wheel)
            0x15, 0x81.toByte(), //       Logical minimum (-127)
            0x25, 0x7F, //       Logical maximum (127)
            0x75, 0x08, //       Report size (8)
            0x95.toByte(), 0x03, //       Report count (3)
            0x81.toByte(), 0x06, //       Input (Data, Variable, Relative)
            0xC0.toByte(),              //    End Collection
            0xC0.toByte(),               // End Collection

            // --- ABSOLUTE DIGITIZER / TOUCH SCREEN (Report ID 3) ---
            0x05, 0x0D, // USAGE_PAGE (Digitizers)
            0x09, 0x04, // USAGE (Touch Screen)
            0xA1.toByte(), 0x01, // COLLECTION (Application)
            0x85.toByte(), ID_DIGITIZER, //   REPORT_ID (3)
            0x09, 0x22, //   USAGE (Finger)
            0xA1.toByte(), 0x00, //   COLLECTION (Physical)
            // Tip Switch (Click) and In-Range
            0x09, 0x42, //     USAGE (Tip Switch)
            0x09, 0x32, //     USAGE (In Range)
            0x15, 0x00, //     LOGICAL_MINIMUM (0)
            0x25, 0x01, //     LOGICAL_MAXIMUM (1)
            0x75, 0x01, //     REPORT_SIZE (1)
            0x95.toByte(), 0x02, //     REPORT_COUNT (2)
            0x81.toByte(), 0x02, //     INPUT (Data,Var,Abs)
            // Padding
            0x95.toByte(), 0x06, //     REPORT_COUNT (6)
            0x81.toByte(), 0x03, //     INPUT (Cnst,Ary,Abs)
            // X and Y
            0x05, 0x01, //     USAGE_PAGE (Generic Desktop)
            0x09, 0x30, //     USAGE (X)
            0x09, 0x31, //     USAGE (Y)
            0x15, 0x00, //     LOGICAL_MINIMUM (0)
            0x26, 0xFF.toByte(), 0x7F, //   LOGICAL_MAXIMUM (32767) - 15 bits is safe for Windows
            0x75, 0x10, //     REPORT_SIZE (16)
            0x95.toByte(), 0x02, //     REPORT_COUNT (2)
            0x81.toByte(), 0x02, //     INPUT (Data,Var,Abs)
            0xC0.toByte(), //   END_COLLECTION
            0xC0.toByte()  // END_COLLECTION
        )

        private val QOS_OUT = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )
    }

    private val bluetoothAdapter: BluetoothAdapter? = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var currentButtonsState: Byte = 0x00
    private var lastHidX: Int = 0
    private var lastHidY: Int = 0

    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "📡 HW Čip status: registered=$registered")
            if (registered) {
                // Připojujeme zpožděně, abychom nenarušili případný handshake z PC
                Handler(Looper.getMainLooper()).postDelayed({
                    forceConnectToBondedDevices()
                }, 1500)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Chybí BLUETOOTH_CONNECT, ignoruji onConnectionStateChanged.")
                return
            }
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "✅ AKTIVNÍ HID SPOJENÍ s PC/TV: ${device.name} [MAC: ${device.address}]")
                    connectedDevice = device
                }
                BluetoothProfile.STATE_CONNECTING -> Log.d(TAG, "⏳ Vytvářím L2CAP kanál k: ${device.name}...")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "❌ Odpojeno od: ${device.name}")
                    if (connectedDevice?.address == device.address) connectedDevice = null
                }
            }
        }
    }

    init {
        initializeProxy()
    }

    private fun initializeProxy() {
        Log.i(TAG, "Inicializuji Bluetooth HID Proxy...")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Chybí oprávnění BLUETOOTH_CONNECT! Nelze inicializovat Proxy.")
                return
            }
        }
        try {
            if (bluetoothAdapter?.isEnabled == true) {
                bluetoothAdapter.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.HID_DEVICE) {
                            Log.i(TAG, "Bluetooth proxy připojeno. Trvale registruji App.")
                            hidDevice = proxy as BluetoothHidDevice
                            registerApp()
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.HID_DEVICE) hidDevice = null
                    }
                }, BluetoothProfile.HID_DEVICE)
            } else {
                Log.e(TAG, "Bluetooth je vypnutý! Nelze získat Proxy.")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Chybí oprávnění BLUETOOTH_CONNECT při získávání Proxy!", e)
        }
    }

    private fun registerApp() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "IrisMotion Mouse",
            "Hardware Eye-Tracker",
            "LlamaCorp",
            BluetoothHidDevice.SUBCLASS1_COMBO.toByte(), // Combo funguje na Windows mnohem lépe!
            COMBO_REPORT_DESCRIPTOR
        )

        val executor = ContextCompat.getMainExecutor(appContext)
        try {
            val result = hidDevice?.registerApp(sdpSettings, null, QOS_OUT, executor, hidCallback)
            Log.i(TAG, "Odeslán požadavek na HW registraci: $result")
        } catch (e: SecurityException) {
            Log.e(TAG, "Chybí oprávnění BLUETOOTH_CONNECT při registraci aplikace!", e)
        }
    }

    fun connectToPc() {
        Log.i(TAG, "🔌 Dlaždice zapnuta: Navazuji spojení s PC")
        if (hidDevice == null) {
            Log.w(TAG, "HID Proxy je null (BT byl možná vypnutý). Znovu inicializuji...")
            initializeProxy()
        } else {
            forceConnectToBondedDevices()
        }
    }

    fun disconnectFromPc() {
        Log.i(TAG, "💤 Dlaždice vypnuta: Odpojuji L2CAP spojení (SDP zůstává aktivní)")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        try {
            connectedDevice?.let {
                hidDevice?.disconnect(it)
                connectedDevice = null
            }
            // DŮLEŽITÉ: Záměrně nevoláme unregisterApp(). Systém Samsung/Snapdragon
            // nezvládá opakované registrování SDP záznamů na pozadí a shazuje celý BT Stack.
        } catch (e: SecurityException) {
            Log.e(TAG, "Chybí oprávnění BLUETOOTH_CONNECT při odpojování!", e)
        }
    }

    fun forceConnectToBondedDevices() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        try {
            val bondedDevices = bluetoothAdapter?.bondedDevices ?: return
            if (bondedDevices.isEmpty()) {
                Log.w(TAG, "Žádná spárovaná zařízení.")
                return
            }

            for (device in bondedDevices) {
                // Filtrujeme pouze počítače! Zabráníme posílání HID požadavků do sluchátek/aut.
                if (device.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER) {
                    Log.i(TAG, "Pokouším se vnutit HID připojení k PC: ${device.name}")
                    val connectResult = hidDevice?.connect(device)
                    Log.i(TAG, "Výsledek pokusu o připojení k ${device.name}: $connectResult")
                } else {
                    Log.d(TAG, "Přeskakuji zařízení ${device.name} (není to počítač)")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Chybí oprávnění BLUETOOTH_CONNECT při spojování s PC!", e)
        }
    }

    fun sendMouseMove(dx: Int, dy: Int, wheel: Int = 0) {
        if (hidDevice == null || connectedDevice == null) return
        val report = ByteArray(4)
        report[0] = currentButtonsState
        report[1] = dx.toByte()
        report[2] = dy.toByte()
        report[3] = wheel.toByte()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        try {
            hidDevice?.sendReport(connectedDevice, ID_MOUSE.toInt(), report)
        } catch (e: SecurityException) {
            Log.e(TAG, "Chybí oprávnění BLUETOOTH_CONNECT při posílání pohybu!", e)
        }
    }

    fun sendMouseClick(buttonMask: Int) {
        if (hidDevice == null || connectedDevice == null) return
        currentButtonsState = (currentButtonsState.toInt() or buttonMask).toByte()
        sendMouseMove(0, 0)
        currentButtonsState = (currentButtonsState.toInt() and buttonMask.inv()).toByte()
        sendMouseMove(0, 0)
    }

    fun sendAbsolutePointer(x: Int, y: Int) {
        if (hidDevice == null || connectedDevice == null) return
        lastHidX = x
        lastHidY = y

        val report = ByteArray(5)

        // Byte 0: Buttons (Bit 0: Tip Switch, Bit 1: In Range)
        // Když je aktivní jakékoliv tlačítko (např. LEFT_CLICK), aktivujeme Tip Switch.
        val isLeftClick = (currentButtonsState.toInt() and LEFT_BUTTON) != 0
        val isMiddleClick = (currentButtonsState.toInt() and MIDDLE_BUTTON) != 0
        val isRightClick = (currentButtonsState.toInt() and RIGHT_BUTTON) != 0

        var digitizerState = 0x02 // Bit 1 = In Range (znamená, že prst je nad tabletem - kurzor se hýbe, ale nekliká)
        if (isLeftClick || isRightClick || isMiddleClick) {
            digitizerState = digitizerState or 0x01 // Bit 0 = Tip Switch (dotyk s deskou - klik/táhnutí)
        }

        report[0] = digitizerState.toByte()

        // Bytes 1-2: X (Little Endian, 16-bit)
        report[1] = (x and 0xFF).toByte()
        report[2] = ((x ushr 8) and 0xFF).toByte()

        // Bytes 3-4: Y (Little Endian, 16-bit)
        report[3] = (y and 0xFF).toByte()
        report[4] = ((y ushr 8) and 0xFF).toByte()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        try {
            hidDevice?.sendReport(connectedDevice, ID_DIGITIZER.toInt(), report)
        } catch (e: SecurityException) {
            Log.e(TAG, "Chybí oprávnění BLUETOOTH_CONNECT při posílání absolutního pohybu!", e)
        }
    }

    fun sendAbsoluteClick(buttonMask: Int) {
        if (hidDevice == null || connectedDevice == null) return
        currentButtonsState = (currentButtonsState.toInt() or buttonMask).toByte()
        sendAbsolutePointer(lastHidX, lastHidY)
        currentButtonsState = (currentButtonsState.toInt() and buttonMask.inv()).toByte()
        sendAbsolutePointer(lastHidX, lastHidY)
    }

    fun release() {
        Log.i(TAG, "Uvolňuji Bluetooth HW... (pouze odpojení, zůstáváme zaregistrováni)")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        try {
            connectedDevice?.let { hidDevice?.disconnect(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Chybí oprávnění BLUETOOTH_CONNECT při uvolňování!", e)
        }
        // DŮLEŽITÉ: NIKDY nevoláme unregisterApp() ani closeProfileProxy()!
    }
}
