package com.simple.proxyconnect.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.simple.proxyconnect.model.ConnectionStatus

@Composable
fun ConnectionButton(
    status: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = when (status) {
        ConnectionStatus.Disconnected -> Color.Gray
        ConnectionStatus.Connecting, ConnectionStatus.Disconnecting -> Color(0xFFFF9800)
        ConnectionStatus.Connected -> Color(0xFF4CAF50)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )

    val size = 150.dp
    val outerSize = 230.dp

    Box(
        modifier = modifier
            .size(outerSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Pulsing ring (only when active)
        if (status.isActive) {
            Canvas(modifier = Modifier.size(size * pulseScale)) {
                drawCircle(
                    color = color.copy(alpha = pulseAlpha),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Background circle
        Canvas(modifier = Modifier.size(size)) {
            // Fill
            drawCircle(
                color = color.copy(alpha = 0.08f)
            )
            // Border
            drawCircle(
                color = color.copy(alpha = 0.35f),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Power icon
        Icon(
            painter = painterResource(id = android.R.drawable.ic_lock_power_off),
            contentDescription = "Toggle connection",
            tint = color,
            modifier = Modifier.size(48.dp)
        )
    }
}
