package com.motordisplay

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.motordisplay.ble.BleManager
import com.motordisplay.databinding.ActivityMainBinding
import com.motordisplay.map.RouteManager
import com.motordisplay.model.RouteInfo
import com.motordisplay.ui.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BleManager
    private lateinit var routeManager: RouteManager

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var currentLocation: Location? = null
    private var destination: GeoPoint? = null
    private var routeInfo: RouteInfo? = null

    private var destinationMarker: Marker? = null
    private var routeLine: Polyline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bleManager = BleManager(this)
        routeManager = RouteManager()
        AppSingleton.bleManager = bleManager

        setupMap()
        setupUi()

        if (PermissionHelper.ensure(this)) {
            onPermissionsReady()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionHelper.REQ_CODE) {
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                onPermissionsReady()
            } else {
                toast("Cần cấp đủ quyền để app hoạt động")
            }
        }
    }

    private fun onPermissionsReady() {
        setupBle()
        requestLocation()
    }

    private fun setupUi() {
        binding.btnSend.setOnClickListener {
            val route = routeInfo
            if (route == null) {
                toast("Chưa có route để gửi")
                return@setOnClickListener
            }
            if (!bleManager.isConnected) {
                toast("ESP32 chưa kết nối")
                bleManager.startScan()
                return@setOnClickListener
            }
            Thread {
                bleManager.sendRoute(route.points)
                runOnUiThread { toast("Đã gửi route sang ESP32") }
            }.start()
        }

        // Nhấn nút "Tìm" để tìm kiếm địa chỉ
        binding.btnSearchDest.setOnClickListener {
            triggerAddressSearch()
        }

        // Nhấn Search trên bàn phím cũng tìm kiếm
        binding.etDestSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerAddressSearch()
                true
            } else false
        }
    }

    private fun triggerAddressSearch() {
        val query = binding.etDestSearch.text.toString().trim()
        if (query.isEmpty()) {
            toast("Nhập địa chỉ cần tìm")
            return
        }
        hideKeyboard()
        searchAddress(query)
    }

    private fun searchAddress(query: String) {
        binding.etDestSearch.setText("Đang tìm \"$query\"...")
        binding.etDestSearch.isEnabled = false

        lifecycleScope.launch {
            val result = forwardGeocode(query)
            binding.etDestSearch.isEnabled = true

            if (result == null) {
                toast("Không tìm thấy địa chỉ: \"$query\"")
                binding.etDestSearch.setText(query)
                return@launch
            }

            val (geoPoint, addressName) = result
            destination = geoPoint
            binding.etDestSearch.setText(addressName)
            binding.mapView.controller.setZoom(17.0)
            binding.mapView.controller.setCenter(geoPoint)
            showDestination(geoPoint)
            buildRoute()
        }
    }

    private fun setupBle() {
        bleManager.onConnectionChanged = { connected ->
            runOnUiThread {
                binding.txtBle.text = if (connected) "ESP32: Đã kết nối" else "ESP32: Chưa kết nối"
            }
        }
        bleManager.startScan()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        binding.txtStart.text = "Đang lấy vị trí..."
        fused.lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    currentLocation = loc
                    binding.txtGps.text = "GPS: Ready"
                    val gp = GeoPoint(loc.latitude, loc.longitude)
                    binding.mapView.controller.setZoom(17.0)
                    binding.mapView.controller.setCenter(gp)
                    // Reverse geocode để hiển thị tên địa chỉ thay vì tọa độ
                    lifecycleScope.launch {
                        val address = reverseGeocode(loc.latitude, loc.longitude)
                        binding.txtStart.text = address
                    }
                } else {
                    binding.txtStart.text = "Không có dữ liệu GPS"
                    binding.txtGps.text = "GPS: Không có dữ liệu"
                }
            }
            .addOnFailureListener {
                binding.txtStart.text = "Lỗi lấy vị trí"
                binding.txtGps.text = "GPS: Lỗi"
            }
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)

        binding.mapView.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
            override fun onLongPress(
                e: android.view.MotionEvent?,
                mapView: org.osmdroid.views.MapView?
            ): Boolean {
                if (e == null || mapView == null) return false
                val point = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                destination = point

                // Reverse geocode điểm được chọn trên bản đồ
                binding.etDestSearch.setText("Đang xác định địa chỉ...")
                lifecycleScope.launch {
                    val address = reverseGeocode(point.latitude, point.longitude)
                    binding.etDestSearch.setText(address)
                }

                showDestination(point)
                buildRoute()
                return true
            }
        })
    }

    // ───────────────────────────────────────────────────────────────
    // Geocoding (Nominatim OpenStreetMap)
    // ───────────────────────────────────────────────────────────────

    /**
     * Chuyển tọa độ → tên địa chỉ (ví dụ: "FPT Tân Thuận 2, Tân Thuận Đông")
     */
    private suspend fun reverseGeocode(lat: Double, lon: Double): String =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://nominatim.openstreetmap.org/reverse" +
                        "?lat=$lat&lon=$lon&format=json&accept-language=vi"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", packageName)
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext coordsText(lat, lon)
                val json = JSONObject(body)
                buildShortAddress(json)
            } catch (e: Exception) {
                coordsText(lat, lon)
            }
        }

    /**
     * Tìm kiếm địa chỉ bằng từ khoá → trả về (GeoPoint, tên địa chỉ)
     */
    private suspend fun forwardGeocode(query: String): Pair<GeoPoint, String>? =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                // countrycodes=vn để ưu tiên kết quả ở Việt Nam
                val url = "https://nominatim.openstreetmap.org/search" +
                        "?q=$encoded&format=json&limit=1&accept-language=vi&countrycodes=vn"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", packageName)
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null
                val arr = JSONArray(body)
                if (arr.length() == 0) return@withContext null

                val first = arr.getJSONObject(0)
                val lat = first.getDouble("lat")
                val lon = first.getDouble("lon")
                // Dùng display_name nhưng rút gọn lấy 3 phần đầu
                val displayName = first.optString("display_name", "")
                val shortName = displayName.split(", ").take(3).joinToString(", ")
                Pair(GeoPoint(lat, lon), shortName)
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Xây dựng tên địa chỉ ngắn gọn từ response Nominatim:
     * ưu tiên: amenity > road/building + suburb > 3 phần đầu display_name
     */
    private fun buildShortAddress(json: JSONObject): String {
        val address = json.optJSONObject("address")
        if (address != null) {
            val amenity  = address.optString("amenity", "")
            val building = address.optString("building", "")
            val road     = address.optString("road", "")
            val suburb   = address.optString("suburb", "")
            val district = address.optString("city_district", "")

            val primary = when {
                amenity.isNotEmpty()  -> amenity
                building.isNotEmpty() -> building
                road.isNotEmpty()     -> road
                else                  -> ""
            }
            val secondary = when {
                suburb.isNotEmpty()   -> suburb
                district.isNotEmpty() -> district
                else                  -> ""
            }
            if (primary.isNotEmpty()) {
                return if (secondary.isNotEmpty()) "$primary, $secondary" else primary
            }
        }
        // Fallback: 3 phần đầu của display_name
        val displayName = json.optString("display_name", "")
        return if (displayName.isNotEmpty())
            displayName.split(", ").take(3).joinToString(", ")
        else
            coordsText(
                json.optDouble("lat", 0.0),
                json.optDouble("lon", 0.0)
            )
    }

    private fun coordsText(lat: Double, lon: Double) = "%.5f, %.5f".format(lat, lon)

    // ───────────────────────────────────────────────────────────────

    private fun showDestination(point: GeoPoint) {
        destinationMarker?.let { binding.mapView.overlays.remove(it) }
        destinationMarker = Marker(binding.mapView).apply {
            position = point
            title = "Destination"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        binding.mapView.overlays.add(destinationMarker)
        binding.mapView.invalidate()
    }

    private fun buildRoute() {
        val loc = currentLocation ?: run {
            toast("Chưa có vị trí hiện tại")
            return
        }
        val dest = destination ?: return

        lifecycleScope.launch {
            binding.txtTurn.text = "Đang tính đường..."
            val result = routeManager.getRoute(
                loc.latitude, loc.longitude,
                dest.latitude, dest.longitude
            )

            if (result == null) {
                toast("Không lấy được route từ OSRM")
                binding.txtTurn.text = "Lỗi route"
                return@launch
            }

            routeInfo = result
            renderRoute(result)
        }
    }

    private fun renderRoute(info: RouteInfo) {
        routeLine?.let { binding.mapView.overlays.remove(it) }

        val polyline = Polyline().apply {
            setPoints(info.points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = android.graphics.Color.BLUE
            outlinePaint.strokeWidth = 12f
        }
        routeLine = polyline
        binding.mapView.overlays.add(polyline)
        binding.mapView.invalidate()

        binding.txtTurn.text = info.instruction
        binding.txtDistance.text = formatDistance(info.distanceMeters)
        binding.txtDistanceBottom.text = "Distance: ${formatDistance(info.distanceMeters)}"
        binding.txtEta.text = "ETA: ${formatDuration(info.durationSeconds)}"
    }

    private fun formatDistance(m: Double): String {
        return if (m >= 1000) "%.1f km".format(m / 1000.0) else "%.0f m".format(m)
    }

    private fun formatDuration(s: Double): String {
        val min = (s / 60.0).toInt()
        return if (min >= 60) {
            val h = min / 60
            val rm = min % 60
            "${h}h ${rm}m"
        } else {
            "${min} min"
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etDestSearch.windowToken, 0)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }
}