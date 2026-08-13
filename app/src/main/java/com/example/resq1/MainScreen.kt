package com.example.resq1

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.resq1.data.Message
import com.example.resq1.ui.theme.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.MarkerOptions
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MainScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    
    Box(modifier = Modifier.fillMaxSize().background(ObsidianMidnight)) {
        // Tab Content with elegant crossfade
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
            },
            label = "TabTransition"
        ) { tab ->
            when (tab) {
                0 -> RadarTab(viewModel)
                1 -> ChatTab(viewModel)
                2 -> MapLibreTab(viewModel)
            }
        }

        // Floating Glass Navigation Dock
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Surface(
                modifier = Modifier
                    .height(64.dp)
                    .width(220.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(1.dp, GlassBorder), CircleShape),
                color = Color.White.copy(alpha = 0.05f)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassNavItem(selectedTab == 0, Icons.Outlined.Radar) { selectedTab = 0 }
                    GlassNavItem(selectedTab == 1, Icons.Outlined.ChatBubbleOutline) { selectedTab = 1 }
                    GlassNavItem(selectedTab == 2, Icons.Outlined.Map) { selectedTab = 2 }
                }
            }
        }
        
        PanicOverlay(viewModel)
    }
}

@Composable
fun GlassNavItem(selected: Boolean, icon: ImageVector, onClick: () -> Unit) {
    val color by animateColorAsState(if (selected) ElectricCyan else TextLow, label = "IconColor")
    val scale by animateFloatAsState(if (selected) 1.2f else 1f, label = "IconScale")
    
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp).graphicsLayer(scaleX = scale, scaleY = scale)
        )
    }
}

@Composable
fun RadarTab(viewModel: MainViewModel) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(64.dp))
        
        Text(
            text = "SCANNING MESH",
            style = MaterialTheme.typography.labelLarge,
            color = ElectricCyan,
            letterSpacing = 4.sp,
            fontWeight = FontWeight.Light
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Orbital Sonar
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            OrbitalRadar(viewModel.myLocation, viewModel.recentPeers.value)
        }
        
        Spacer(modifier = Modifier.height(32.dp))

        // Background Trigger Setup (if not enabled)
        if (activity?.isAccessibilityServiceEnabled() == false) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(BorderStroke(1.dp, WarningOrange.copy(alpha = 0.3f)), RoundedCornerShape(16.dp)),
                color = Color.White.copy(alpha = 0.03f)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SettingsSuggest, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("BACKGROUND TRIGGER OFF", color = TextHigh, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Enable for Volume-button SOS", color = TextLow, fontSize = 9.sp)
                    }
                    TextButton(onClick = { activity.openAccessibilitySettings() }) {
                        Text("SETUP", color = WarningOrange, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        
        // Status Glass Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(24.dp)),
            color = Color.White.copy(alpha = 0.03f)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ACTIVE NODES", color = TextLow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${viewModel.activeNodes.size}", color = TextHigh, fontSize = 24.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace)
                }
                
                // SOS Quick Action
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(DangerGlow.copy(alpha = 0.2f))
                        .border(BorderStroke(1.dp, DangerGlow), CircleShape)
                        .clickable { viewModel.triggerPanicMode() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = DangerGlow, modifier = Modifier.size(24.dp))
                }
            }
        }
        
        Spacer(modifier = Modifier.height(120.dp)) // Offset for the dock
    }
}

@Composable
fun OrbitalRadar(myLoc: Pair<Double, Double>?, peers: List<Message>) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val sweepRotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(4000, easing = LinearEasing)),
        label = "Sweep"
    )

    Canvas(modifier = Modifier.size(300.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        
        // Orbital Rings
        for (i in 1..3) {
            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = (size.width / 6) * i,
                center = center,
                style = Stroke(1.dp.toPx())
            )
        }

        // Animated Sweep
        rotate(sweepRotation, center) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f to ElectricCyan.copy(alpha = 0.5f),
                    0.1f to Color.Transparent,
                    center = center
                ),
                startAngle = -90f,
                sweepAngle = 90f,
                useCenter = true,
                size = size
            )
        }

        // User Node
        drawCircle(ElectricCyan, radius = 4.dp.toPx(), center = center)
        drawCircle(ElectricCyan.copy(alpha = 0.2f), radius = 12.dp.toPx(), center = center)

        // Peer Nodes
        if (myLoc != null) {
            val scale = 150000f
            peers.forEach { peer ->
                val x = center.x + ((peer.lon ?: 0.0) - myLoc.second).toFloat() * scale
                val y = center.y - ((peer.lat ?: 0.0) - myLoc.first).toFloat() * scale
                
                // Glowing Point
                drawCircle(SignalGreen, radius = 3.dp.toPx(), center = Offset(x, y))
                drawCircle(SignalGreen.copy(alpha = 0.2f), radius = 8.dp.toPx(), center = Offset(x, y))
            }
        }
    }
}

@Composable
fun ChatTab(viewModel: MainViewModel) {
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    
    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) listState.animateScrollToItem(viewModel.messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(modifier = Modifier.height(64.dp))
        
        // Channel Selector
        var expanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TacticalHeader("CHANNEL: ${viewModel.currentRoom}")
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.FilterList, contentDescription = "Switch Room", tint = ElectricCyan)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(ObsidianSurface)) {
                    listOf("GENERAL", "MEDICAL", "SUPPLY").forEach { room ->
                        DropdownMenuItem(
                            text = { Text(room, color = TextHigh, fontWeight = FontWeight.Bold) },
                            onClick = { viewModel.switchRoom(room); expanded = false }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(viewModel.messages) { message -> GlassMessageItem(message) }
        }

        // Minimalist Input Bar
        Surface(
            modifier = Modifier
                .padding(bottom = 110.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(CircleShape)
                .border(BorderStroke(1.dp, GlassBorder), CircleShape),
            color = Color.White.copy(alpha = 0.05f)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = TextHigh,
                        unfocusedTextColor = TextHigh,
                        cursorColor = ElectricCyan,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    placeholder = { Text("Broadcast...", color = TextLow, fontSize = 14.sp) }
                )
                
                IconButton(
                    onClick = { if (text.isNotBlank()) { viewModel.sendMessage(text); text = "" } },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ElectricCyan)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = ObsidianMidnight, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun GlassMessageItem(message: Message) {
    val isMe = message.sender == "Me"
    val alignment = if (isMe) Alignment.End else Alignment.Start
    val bubbleColor = if (message.isSos) DangerGlow.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)
    val borderColor = if (message.isSos) DangerGlow.copy(alpha = 0.5f) else GlassBorder

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(20.dp)),
            color = bubbleColor
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = message.sender.uppercase(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (message.isSos) DangerGlow else ElectricCyan
                    )
                    if (message.rssi != 0) {
                        Text("${message.rssi}dBm", fontSize = 8.sp, color = TextLow, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = message.content, color = TextHigh, fontSize = 15.sp)
                
                if (message.lat != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${String.format(Locale.US, "%.4f", message.lat)}, ${String.format(Locale.US, "%.4f", message.lon)}",
                        fontSize = 8.sp, color = TextLow, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun MapLibreTab(viewModel: MainViewModel) {
    val myLoc = viewModel.myLocation
    val peers = viewModel.recentPeers.value

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                getMapAsync { map ->
                    // Use a stable, public MapLibre style URL to prevent the "Dark" style crash
                    map.setStyle("https://demotiles.maplibre.org/style.json")
                    map.uiSettings.isCompassEnabled = false
                    map.uiSettings.isLogoEnabled = false
                    map.uiSettings.isAttributionEnabled = false
                    
                    if (myLoc != null) {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(myLoc.first, myLoc.second), 14.0))
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { map ->
                map.clear()
                
                // Add Current User
                myLoc?.let {
                    map.addMarker(MarkerOptions()
                        .position(LatLng(it.first, it.second))
                        .title("ME"))
                }

                // Add Peers
                peers.forEach { peer ->
                    if (peer.lat != null && peer.lon != null) {
                        map.addMarker(MarkerOptions()
                            .position(LatLng(peer.lat, peer.lon))
                            .title(peer.sender)
                            .snippet(peer.content))
                    }
                }
            }
        }
    )
}

@Composable
fun PanicOverlay(viewModel: MainViewModel) {
    AnimatedVisibility(
        visible = viewModel.isRecording || viewModel.panicStatus != null,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianMidnight.copy(alpha = 0.7f))
                    .blur(15.dp)
            )

            Surface(
                modifier = Modifier
                    .padding(32.dp)
                    .widthIn(max = 400.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .border(BorderStroke(1.dp, GlassBorder), RoundedCornerShape(32.dp)),
                color = ObsidianSurface.copy(alpha = 0.9f)
            ) {
                Column(
                    modifier = Modifier.padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val icon = if (viewModel.isRecording) Icons.Default.Mic else Icons.Default.CheckCircle
                    val tint = if (viewModel.isRecording) DangerGlow else SignalGreen
                    
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = if (viewModel.isRecording) "RECORDING" else "TRIAGE ANALYZED",
                        color = tint,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = viewModel.panicStatus ?: "Speak into the mesh...",
                        color = TextHigh,
                        textAlign = TextAlign.Center,
                        fontSize = 18.sp
                    )
                    if (!viewModel.isRecording) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { viewModel.panicStatus = null },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f))
                        ) {
                            Text("DISMISS", color = TextHigh)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TacticalHeader(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(4.dp, 12.dp).background(ElectricCyan))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, color = TextHigh, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
    }
}
