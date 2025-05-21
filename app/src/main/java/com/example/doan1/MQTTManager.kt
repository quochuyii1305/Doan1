package com.example.plantwatering

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.util.UUID
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MqttManager(private val context: Context) {

    private val serverUri = "ssl://d39af0392518478980c1e73505500d1b.s1.eu.hivemq.cloud:8883"
    private val clientId = "AndroidClient-" + UUID.randomUUID().toString() // Sử dụng UUID cho client ID duy nhất
    private val username = "hivemq.webclient.1745571789362"
    private val password = "M4hD@2rH9w3Qu:a#V<lK"

    private val topicControlWatering = "control/watering"
    private val topicDataSoil = "data/soil"
    private val topicStatusWatering = "status/watering"    // Topic nhận trạng thái tưới
    private val topicStopWatering = "control/stop"         // Topic để gửi lệnh dừng tưới
    private val topicControlRefresh = "control/refresh"    // Topic để yêu cầu cập nhật dữ liệu

    private var mqttClient: MqttClient? = null
    private var isConnected = false

    // StateFlow để UI components theo dõi dữ liệu một cách reactive
    private val _moistureDataFlow = MutableStateFlow(mapOf(0 to 0, 1 to 0, 2 to 0))
    val moistureDataFlow: StateFlow<Map<Int, Int>> = _moistureDataFlow.asStateFlow()

    // StateFlow cho trạng thái tưới
    private val _isWateringFlow = MutableStateFlow(false)
    val isWateringFlow: StateFlow<Boolean> = _isWateringFlow.asStateFlow()

    private val _wateringZoneFlow = MutableStateFlow(-1)
    val wateringZoneFlow: StateFlow<Int> = _wateringZoneFlow.asStateFlow()

    // Truy cập trực tiếp vào dữ liệu
    val moistureData: Map<Int, Int>
        get() = _moistureDataFlow.value

    val isSystemWatering: Boolean
        get() = _isWateringFlow.value

    val wateringZone: Int
        get() = _wateringZoneFlow.value

    // Callbacks
    var onConnectionLost: (() -> Unit)? = null

    fun connect(onConnected: () -> Unit, onFailure: (String) -> Unit) {
        try {
            // Khởi tạo MqttClient
            mqttClient = MqttClient(serverUri, clientId, MqttDefaultFilePersistence(context.filesDir.absolutePath))

            val connectOptions = MqttConnectOptions().apply {
                isCleanSession = true
                userName = username
                password = this@MqttManager.password.toCharArray()
                connectionTimeout = 30
                keepAliveInterval = 60
            }

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.d(TAG, "MQTT Connection lost: ${cause?.message}")
                    isConnected = false
                    onConnectionLost?.invoke()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    Log.d(TAG, "Message received: ${message?.toString()} from $topic")

                    if (topic == topicDataSoil && message != null) {
                        try {
                            val jsonObject = JSONObject(message.toString())

                            // Cập nhật dữ liệu độ ẩm sử dụng StateFlow
                            val updatedMap = mutableMapOf<Int, Int>()
                            updatedMap[0] = jsonObject.optInt("moisture0", 0)
                            updatedMap[1] = jsonObject.optInt("moisture1", 0)
                            updatedMap[2] = jsonObject.optInt("moisture2", 0)
                            _moistureDataFlow.value = updatedMap

                            // Cập nhật trạng thái tưới từ ESP32 (khi ESP32 tự động tưới)
                            if (jsonObject.has("isWatering") && jsonObject.has("wateringZone")) {
                                val isWatering = jsonObject.getBoolean("isWatering")
                                val zone = jsonObject.getInt("wateringZone")

                                // Cập nhật StateFlow
                                _isWateringFlow.value = isWatering
                                _wateringZoneFlow.value = if (isWatering) zone else -1
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing moisture data: ${e.message}")
                        }
                    }
                    else if (topic == topicStatusWatering && message != null) {
                        try {
                            val jsonObject = JSONObject(message.toString())
                            val isWatering = jsonObject.optBoolean("isWatering", false)
                            val zone = jsonObject.optInt("zone", -1)

                            // Cập nhật StateFlow
                            _isWateringFlow.value = isWatering
                            _wateringZoneFlow.value = if (isWatering) zone else -1

                            Log.d(TAG, "Watering status updated: isWatering=$isWatering, zone=$zone")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing watering status: ${e.message}")
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Log.d(TAG, "Message delivery complete")
                }
            })

            // Kết nối trong thread nền
            Thread {
                try {
                    mqttClient?.connect(connectOptions)
                    Log.d(TAG, "MQTT Connection success")
                    isConnected = true
                    subscribeToTopics()
                    // Yêu cầu cập nhật dữ liệu mới nhất ngay sau khi kết nối
                    requestDataRefresh()
                    onConnected()
                } catch (e: MqttException) {
                    Log.e(TAG, "MQTT Connection failed: ${e.message}")
                    isConnected = false
                    onFailure("Failed to connect to MQTT broker: ${e.message}")
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "MQTT Connection exception: ${e.message}")
            onFailure("Error while connecting: ${e.message}")
        }
    }

    private fun subscribeToTopics() {
        try {
            // Đăng ký topic dữ liệu đất
            mqttClient?.subscribe(topicDataSoil, 1)
            Log.d(TAG, "Subscribed to $topicDataSoil")

            // Đăng ký topic trạng thái tưới
            mqttClient?.subscribe(topicStatusWatering, 1)
            Log.d(TAG, "Subscribed to $topicStatusWatering")
        } catch (e: MqttException) {
            Log.e(TAG, "Error subscribing: ${e.message}")
        }
    }

    // Hàm yêu cầu ESP32 gửi dữ liệu mới nhất
    fun requestDataRefresh() {
        if (!isConnected || mqttClient == null) {
            return
        }

        try {
            val message = MqttMessage().apply {
                payload = "refresh".toByteArray()
                qos = 1
                isRetained = false
            }

            mqttClient?.publish(topicControlRefresh, message)
            Log.d(TAG, "Refresh data request sent successfully")
        } catch (e: MqttException) {
            Log.e(TAG, "Error sending refresh request: ${e.message}")
        }
    }

    fun controlWatering(plantId: Int, duration: Int, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        if (!isConnected || mqttClient == null) {
            onFailure("Not connected to the MQTT broker")
            return
        }

        try {
            val jsonMessage = JSONObject().apply {
                put("plant", plantId)
                put("duration", duration)
            }

            val message = MqttMessage().apply {
                payload = jsonMessage.toString().toByteArray()
                qos = 1
                isRetained = false
            }

            mqttClient?.publish(topicControlWatering, message)
            Log.d(TAG, "Watering command sent successfully")
            onSuccess()

        } catch (e: MqttException) {
            Log.e(TAG, "Error sending message: ${e.message}")
            onFailure("Error sending: ${e.message}")
        }
    }

    fun stopWatering(zone: Int, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        if (!isConnected || mqttClient == null) {
            onFailure("Not connected to the MQTT broker")
            return
        }

        try {
            val jsonMessage = JSONObject().apply {
                put("zone", zone)
            }

            val message = MqttMessage().apply {
                payload = jsonMessage.toString().toByteArray()
                qos = 1
                isRetained = false
            }

            mqttClient?.publish(topicStopWatering, message)
            Log.d(TAG, "Stop watering command sent successfully")
            onSuccess()

        } catch (e: MqttException) {
            Log.e(TAG, "Error sending stop command: ${e.message}")
            onFailure("Error sending stop command: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            if (mqttClient?.isConnected == true) {
                mqttClient?.disconnect()
                Log.d(TAG, "Disconnected from broker")
                isConnected = false
            }
        } catch (e: MqttException) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        } finally {
            try {
                mqttClient?.close()
            } catch (e: MqttException) {
                Log.e(TAG, "Error closing client: ${e.message}")
            }
        }
    }

    fun isConnected(): Boolean {
        return isConnected && mqttClient?.isConnected == true
    }

    companion object {
        private const val TAG = "MqttManager"
    }
}