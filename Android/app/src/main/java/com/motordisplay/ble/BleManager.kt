package com.motordisplay.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.motordisplay.model.RoutePoint
import java.util.UUID

class BleManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val ROUTE_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val NOTIF_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9")
        const val DEVICE_NAME = "ESP32-NavDisplay"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager.adapter
    private var gatt: BluetoothGatt? = null
    private var routeChar: BluetoothGattCharacteristic? = null
    private var notifChar: BluetoothGattCharacteristic? = null

    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var isConnected = false
        private set

    // FIX #3: Kiểm tra permission thật sự thay vì @SuppressLint
    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 11 trở xuống không cần BLUETOOTH_SCAN
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            // FIX: check permission trước khi đọc device.name
            if (!hasScanPermission()) return
            @Suppress("MissingPermission")
            if (d.name == DEVICE_NAME) {
                adapter?.bluetoothLeScanner?.stopScan(this)
                connect(d)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!hasConnectPermission()) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                @Suppress("MissingPermission")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                handler.post { onConnectionChanged?.invoke(false) }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!hasConnectPermission()) return
            @Suppress("MissingPermission")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID) ?: return
            routeChar = service.getCharacteristic(ROUTE_CHAR_UUID)
            notifChar = service.getCharacteristic(NOTIF_CHAR_UUID)
            isConnected = true
            handler.post { onConnectionChanged?.invoke(true) }
        }
    }

    fun startScan() {
        // FIX #3: Thoát sớm nếu chưa có permission, không để crash
        if (!hasScanPermission()) return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        @Suppress("MissingPermission")
        adapter?.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)

        handler.postDelayed({
            if (hasScanPermission()) {
                @Suppress("MissingPermission")
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            }
        }, 12000)
    }

    private fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) return
        gatt?.close()
        @Suppress("MissingPermission")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun sendRoute(points: List<RoutePoint>) {
        if (!isConnected || routeChar == null || gatt == null) return
        write(routeChar!!, "ROUTE:START:${points.size}")
        Thread.sleep(60)

        points.chunked(8).forEach { chunk ->
            val body = chunk.joinToString("|") {
                "%.6f,%.6f".format(it.lat, it.lon)
            }
            write(routeChar!!, "ROUTE:PTS:$body")
            Thread.sleep(60)
        }

        write(routeChar!!, "ROUTE:END")
    }

    fun sendNotification(app: String, sender: String, body: String) {
        if (!isConnected || notifChar == null || gatt == null) return
        val safe = body.take(80).replace("|", " ")
        write(notifChar!!, "NOTIF:MSG:$app|$sender|$safe")
    }

    fun sendCall(app: String, caller: String) {
        if (!isConnected || notifChar == null || gatt == null) return
        write(notifChar!!, "NOTIF:CALL:$app|$caller")
    }

    private fun write(ch: BluetoothGattCharacteristic, value: String) {
        if (!hasConnectPermission()) return
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = value.toByteArray(Charsets.UTF_8)
        @Suppress("MissingPermission")
        gatt?.writeCharacteristic(ch)
    }
}