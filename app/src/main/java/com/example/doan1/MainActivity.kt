package com.example.plantwatering

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var mqttManager: MqttManager
    private val wateringDuration = 20000 // 20 giây mặc định

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mqttManager = MqttManager(applicationContext)

        setContent {
            MaterialTheme {
                WateringControlScreen(mqttManager, wateringDuration)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttManager.disconnect()
    }
}

@Composable
fun WateringControlScreen(mqttManager: MqttManager, wateringDuration: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connectionStatus by remember { mutableStateOf("Không được kết nối") }
    var isConnecting by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }

    // Theo dõi dữ liệu từ StateFlow
    val moistureValues by mqttManager.moistureDataFlow.collectAsState()
    val isSystemWatering by mqttManager.isWateringFlow.collectAsState()
    val currentWateringZone by mqttManager.wateringZoneFlow.collectAsState()

    // Theo dõi thời gian cập nhật dữ liệu
    var lastUpdateTime by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }

    // Lấy thông tin kích thước màn hình
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val screenWidth = configuration.screenWidthDp.dp

    // Auto refresh mỗi 15 giây
    LaunchedEffect(isConnected) {
        if (isConnected) {
            while (true) {
                delay(15000)
                if (isConnected) {
                    mqttManager.requestDataRefresh()
                    lastUpdateTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                }
            }
        }
    }

    // Kết nối ban đầu
    LaunchedEffect(Unit) {
        isConnecting = true
        mqttManager.connect(
            onConnected = {
                connectionStatus = "Đã kết nối với broker MQTT"
                isConnected = true
                isConnecting = false
                lastUpdateTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
            },
            onFailure = { error ->
                connectionStatus = "Thất bại: $error"
                isConnected = false
                isConnecting = false
            }
        )

        mqttManager.onConnectionLost = {
            connectionStatus = "Mất kết nối"
            isConnected = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp) // Giảm padding
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Hệ thống tưới cây thông minh",
            fontSize = 20.sp, // Giảm fontSize cho màn hình nhỏ
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
            textAlign = TextAlign.Center
        )

        // Card trạng thái kết nối
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp) // Giảm padding
        ) {
            Column(
                modifier = Modifier.padding(12.dp) // Giảm padding
            ) {
                Text(
                    text = "Trạng thái kết nối: $connectionStatus",
                    color = if (isConnected) Color.Green else Color.Red,
                    fontSize = 14.sp // Giảm fontSize
                )

                if (isConnecting) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(4.dp) // Giảm chiều cao
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isConnected) {
                        Button(
                            onClick = {
                                mqttManager.disconnect()
                                connectionStatus = "Không được kết nối"
                                isConnected = false
                                Toast.makeText(context, "Đã ngắt kết nối với broker MQTT", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Red
                            ),
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .height(36.dp) // Giảm chiều cao nút
                        ) {
                            Text("Ngắt kết nối", fontSize = 12.sp)
                        }
                    }

                    Button(
                        onClick = {
                            isConnecting = true
                            mqttManager.connect(
                                onConnected = {
                                    connectionStatus = "Đã kết nối với broker MQTT"
                                    isConnected = true
                                    isConnecting = false
                                    lastUpdateTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                        .format(java.util.Date())
                                },
                                onFailure = { error ->
                                    connectionStatus = "Thất bại: $error"
                                    isConnected = false
                                    isConnecting = false
                                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                }
                            )
                        },
                        enabled = !isConnecting && !isConnected,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Kết nối", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Hiển thị trạng thái đang tưới toàn hệ thống
        if (isSystemWatering) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp), // Giảm padding
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Thông báo",
                        tint = Color.Blue,
                        modifier = Modifier
                            .size(20.dp) // Giảm kích thước icon
                            .padding(end = 4.dp)
                    )
                    Text(
                        text = "Đang tưới khu ${currentWateringZone + 1}. Vui lòng đợi.",
                        color = Color.Blue,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp // Giảm fontSize
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Card dữ liệu độ ẩm đất
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .heightIn(max = screenHeight * 0.4f), // Giới hạn chiều cao
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dữ liệu độ ẩm đất",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = {
                            if (isConnected && !isRefreshing) {
                                isRefreshing = true
                                scope.launch {
                                    mqttManager.requestDataRefresh()
                                    lastUpdateTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                        .format(java.util.Date())
                                    delay(1000)
                                    isRefreshing = false
                                }
                            }
                        },
                        enabled = isConnected && !isRefreshing,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Làm mới dữ liệu",
                            tint = if (isRefreshing) Color.Gray else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (isRefreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(4.dp)
                    )
                }

                if (lastUpdateTime.isNotEmpty()) {
                    Text(
                        text = "Cập nhật lúc: $lastUpdateTime",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                MoistureDisplayRow("Khu 1", moistureValues[0] ?: 0)
                MoistureDisplayRow("Khu 2", moistureValues[1] ?: 0)
                MoistureDisplayRow("Khu 3", moistureValues[2] ?: 0)

                Text(
                    text = "Giá trị càng thấp, đất càng ẩm",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Card điều khiển tưới cây
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .heightIn(max = screenHeight * 0.4f), // Giới hạn chiều cao
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = "Điều khiển tưới cây",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                WateringZoneControl(
                    zoneName = "Khu 1",
                    isWatering = isSystemWatering && currentWateringZone == 0,
                    isConnected = isConnected,
                    isSystemBusy = isSystemWatering,
                    onWateringRequest = {
                        mqttManager.controlWatering(
                            plantId = 0,
                            duration = wateringDuration,
                            onSuccess = {
                                Toast.makeText(context, "Tưới khu 1 đã được kích hoạt", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(context, "Lỗi: $error", Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    onStopWatering = {
                        if (isSystemWatering && currentWateringZone == 0) {
                            mqttManager.stopWatering(
                                zone = 0,
                                onSuccess = {
                                    Toast.makeText(context, "Đã gửi lệnh dừng tưới khu 1", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = { error ->
                                    Toast.makeText(context, "Lỗi dừng tưới: $error", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                )

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                WateringZoneControl(
                    zoneName = "Khu 2",
                    isWatering = isSystemWatering && currentWateringZone == 1,
                    isConnected = isConnected,
                    isSystemBusy = isSystemWatering,
                    onWateringRequest = {
                        mqttManager.controlWatering(
                            plantId = 1,
                            duration = wateringDuration,
                            onSuccess = {
                                Toast.makeText(context, "Tưới khu 2 đã được kích hoạt", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(context, "Lỗi: $error", Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    onStopWatering = {
                        if (isSystemWatering && currentWateringZone == 1) {
                            mqttManager.stopWatering(
                                zone = 1,
                                onSuccess = {
                                    Toast.makeText(context, "Đã gửi lệnh dừng tưới khu 2", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = { error ->
                                    Toast.makeText(context, "Lỗi dừng tưới: $error", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                )

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                WateringZoneControl(
                    zoneName = "Khu 3",
                    isWatering = isSystemWatering && currentWateringZone == 2,
                    isConnected = isConnected,
                    isSystemBusy = isSystemWatering,
                    onWateringRequest = {
                        mqttManager.controlWatering(
                            plantId = 2,
                            duration = wateringDuration,
                            onSuccess = {
                                Toast.makeText(context, "Tưới khu 3 đã được kích hoạt", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(context, "Lỗi: $error", Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    onStopWatering = {
                        if (isSystemWatering && currentWateringZone == 2) {
                            mqttManager.stopWatering(
                                zone = 2,
                                onSuccess = {
                                    Toast.makeText(context, "Đã gửi lệnh dừng tưới khu 3", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = { error ->
                                    Toast.makeText(context, "Lỗi dừng tưới: $error", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "© 2025 Hệ thống tưới cây thông minh",
            fontSize = 10.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
    }
}

@Composable
fun MoistureDisplayRow(zoneName: String, value: Int) {
    val maxValue = 4095

    val moistureStatus = when {
        value < 300 -> Pair("Rất ẩm", Color(0xFF1B5E20))
        value < 2000 -> Pair("Ẩm", Color(0xFF4CAF50))
        value < 3500 -> Pair("Trung bình", Color(0xFFFFC107))
        else -> Pair("Khô", Color(0xFFF44336))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp) // Giảm padding
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = zoneName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                text = "$value / $maxValue - ${moistureStatus.first}",
                color = moistureStatus.second,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
        }

        LinearProgressIndicator(
            progress = 1f - (value.toFloat() / maxValue),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp) // Giảm chiều cao
                .padding(top = 2.dp),
            color = moistureStatus.second
        )
    }
}

@Composable
fun WateringZoneControl(
    zoneName: String,
    isWatering: Boolean,
    isConnected: Boolean,
    isSystemBusy: Boolean,
    onWateringRequest: () -> Unit,
    onStopWatering: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp), // Giảm padding
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = zoneName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            if (isWatering) {
                Text(
                    text = "Đang tưới...",
                    color = Color.Blue,
                    fontSize = 10.sp
                )
            }
        }

        Row {
            if (isWatering) {
                Button(
                    onClick = { onStopWatering() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red
                    ),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .height(32.dp) // Giảm chiều cao nút
                ) {
                    Text(text = "Dừng tưới", fontSize = 12.sp)
                }
            }

            Button(
                onClick = { onWateringRequest() },
                enabled = isConnected && !isSystemBusy,
                modifier = Modifier.height(32.dp)
            ) {
                Text(text = "Tưới", fontSize = 12.sp)
            }
        }
    }
}