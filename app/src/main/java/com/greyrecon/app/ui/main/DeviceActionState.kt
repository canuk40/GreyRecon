package com.greyrecon.app.ui.main

import com.greyrecon.app.engine.cve.CveFinding
import com.greyrecon.app.engine.model.Port
import com.greyrecon.app.engine.model.ShodanFinding
import com.greyrecon.app.engine.security.DefaultCredsHit
import com.greyrecon.app.engine.security.ExposureFinding
import com.greyrecon.app.engine.security.SecurityCheckResult
import com.greyrecon.app.engine.snmp.SnmpInfo

sealed class ActionResult<out T> {
    data object Idle : ActionResult<Nothing>()
    data object Loading : ActionResult<Nothing>()
    data class Success<T>(val value: T) : ActionResult<T>()
    data class Error(val message: String) : ActionResult<Nothing>()
}

/** Per-device results for the on-demand actions: port scan, AI explain, Shodan lookup, Wake-on-LAN, security check, NVD lookup. */
data class DeviceActions(
    val ports: ActionResult<List<Port>> = ActionResult.Idle,
    val aiResponse: ActionResult<String> = ActionResult.Idle,
    val shodanFindings: ActionResult<List<ShodanFinding>> = ActionResult.Idle,
    val wakeOnLan: ActionResult<String> = ActionResult.Idle,
    val securityCheck: ActionResult<SecurityCheckResult> = ActionResult.Idle,
    val nvdFindings: ActionResult<List<CveFinding>> = ActionResult.Idle,
    val snmpInfo: ActionResult<SnmpInfo> = ActionResult.Idle,
    val snmpWalk: ActionResult<Map<String, String>> = ActionResult.Idle,
    val snmpBruteForce: ActionResult<Pair<String, SnmpInfo>> = ActionResult.Idle,
    val exposures: ActionResult<List<ExposureFinding>> = ActionResult.Idle,
    val defaultCreds: ActionResult<DefaultCredsHit> = ActionResult.Idle,
)
