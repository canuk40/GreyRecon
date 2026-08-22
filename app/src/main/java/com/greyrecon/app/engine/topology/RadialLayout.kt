package com.greyrecon.app.engine.topology

import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.engine.model.DeviceType
import kotlin.math.cos
import kotlin.math.sin

/**
 * GreyRecon has no way to see real Layer-2 topology (switch ports, VLANs) -- that needs SNMP
 * access to managed switches, out of scope for an unprivileged endpoint app. What's computed here
 * is a logical star: the gateway at the center, every other device as a spoke around it, grouped
 * into one node per [DeviceType] rather than one spoke per device (a flat 40+-device network would
 * be unreadable as individual spokes). This is an honest match for what a typical flat home/small
 * office network actually looks like from a single endpoint's vantage point -- not a claim about
 * physical wiring.
 */
data class ClusterNode(
    val type: DeviceType,
    val devices: List<Device>,
    val x: Float,
    val y: Float,
)

data class TopologyLayout(
    val centerX: Float,
    val centerY: Float,
    val gateway: Device?,
    val clusters: List<ClusterNode>,
)

object RadialLayout {

    fun compute(devices: List<Device>, width: Float, height: Float): TopologyLayout {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) * 0.35f

        val gateway = devices.firstOrNull { it.isGateway }
        val rest = devices.filterNot { it.isGateway }
        val grouped = rest.groupBy { it.deviceType }.toList()

        val clusters = grouped.mapIndexed { index, (type, group) ->
            val angle = (2 * Math.PI * index / grouped.size.coerceAtLeast(1)) - Math.PI / 2
            ClusterNode(
                type = type,
                devices = group,
                x = centerX + radius * cos(angle).toFloat(),
                y = centerY + radius * sin(angle).toFloat(),
            )
        }

        return TopologyLayout(centerX, centerY, gateway, clusters)
    }
}
