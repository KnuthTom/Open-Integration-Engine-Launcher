/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.screens

import androidx.compose.runtime.Composable
import com.gestalt.app.AppDialog
import com.gestalt.app.AppState
import com.gestalt.app.Route
import com.gestalt.app.diagnose.Diagnosis
import com.gestalt.app.diagnose.FailureKind
import com.gestalt.app.diagnose.FixAction
import com.gestalt.app.ui.CertDetailsView
import com.gestalt.app.ui.InlineNote
import com.gestalt.app.ui.KeyValueRow
import com.gestalt.app.ui.ModalOverlay
import com.gestalt.design.components.GestaltButton
import com.gestalt.design.components.GestaltText
import com.gestalt.design.components.StatusLevel
import com.gestalt.design.components.TextRole
import com.gestalt.design.theme.ButtonStyle
import com.gestalt.design.theme.GestaltTheme
import com.gestalt.launcher.diag.CheckResult
import com.gestalt.launcher.diag.CheckStatus
import com.gestalt.launcher.diag.TlsFailureClass

/** Renders whichever modal [AppState.dialog] currently holds, over the shell. */
@Composable
fun AppDialogs(appState: AppState) {
    when (val d = appState.dialog) {
        null -> Unit
        is AppDialog.Diagnose -> DiagnoseDialog(appState, d)
        is AppDialog.TrustPin -> TrustPinDialog(appState, d)
        is AppDialog.CertView -> CertViewDialog(appState, d)
        is AppDialog.TestResult -> TestResultDialog(appState, d)
        is AppDialog.Notice -> NoticeDialog(appState, d)
    }
}

@Composable
private fun NoticeDialog(appState: AppState, d: AppDialog.Notice) {
    ModalOverlay(
        title = d.title,
        onDismiss = appState::dismissDialog,
        actions = { GestaltButton("OK", onClick = appState::dismissDialog) },
    ) {
        GestaltText(d.message, role = TextRole.Body)
    }
}

@Composable
private fun TestResultDialog(appState: AppState, d: AppDialog.TestResult) {
    val report = d.report
    ModalOverlay(
        title = "Connection test — ${report.connectionName}",
        onDismiss = appState::dismissDialog,
        actions = {
            // An untrusted/hostname-mismatched leaf routes to the managed, audited trust-pin flow — never
            // a "trust anyway" bypass. Only when this is a SAVED connection (we have a pin target).
            if (report.needsTrustDecision && d.connectionId != null) {
                GestaltButton("Review & trust certificate", onClick = { appState.beginTrustPrompt(d.connectionId) })
            }
            GestaltButton("View logs", style = ButtonStyle.Ghost, onClick = appState::openLogsFolder)
            GestaltButton("Close", style = ButtonStyle.Ghost, onClick = appState::dismissDialog)
        },
    ) {
        // An untrusted/hostname-mismatched leaf is a pin-able trust decision, not a hard error — surface it
        // as an actionable headline (and amber per-check), so the "Review & trust certificate" action reads
        // as the next step rather than a dead red FAIL.
        InlineNote(
            when {
                report.ok -> StatusLevel.Success
                report.needsTrustDecision -> StatusLevel.Warning
                else -> StatusLevel.Error
            },
            "${report.headline()} — ${report.target}",
        )
        report.checks.forEach { c ->
            InlineNote(levelFor(c), "${badgeText(c)}  ${c.name}")
            // Lead with the plain-language line when we have one; keep the raw detail visible but muted
            // (the full, unabridged detail also goes to logs/diagnostics.txt via "View logs").
            val plain = c.plainLanguage
            if (plain != null) {
                GestaltText(plain, role = TextRole.Body)
                GestaltText(c.detail, role = TextRole.Label, color = GestaltTheme.colors.textMuted)
            } else {
                GestaltText(c.detail, role = TextRole.Body)
            }
        }
        report.leaf?.let { l ->
            GestaltText("Server certificate", role = TextRole.Heading)
            KeyValueRow("Subject", l.subject)
            KeyValueRow("Issuer", l.issuer)
            KeyValueRow("SANs", if (l.sans.isEmpty()) "(none)" else l.sans.joinToString(", "))
            KeyValueRow("Validity", "${l.notBefore}  ..  ${l.notAfter}")
            KeyValueRow("SHA-256", l.sha256Display, mono = true)
        }
        if (report.needsTrustDecision && d.connectionId == null) {
            InlineNote(StatusLevel.Warning, "Save this connection first to review and pin its certificate.")
        }
    }
}

/**
 * The badge level for a check. A failure that is really a pin-able trust decision (untrusted self-signed
 * leaf or a name mismatch) renders amber (Warning) — it's an action to take, not a hard error — so the
 * panel never shows a bare red FAIL for something the user can resolve by pinning.
 */
private fun levelFor(c: CheckResult): StatusLevel = when {
    isTrustDecision(c) -> StatusLevel.Warning
    c.status == CheckStatus.PASS -> StatusLevel.Success
    c.status == CheckStatus.FAIL -> StatusLevel.Error
    c.status == CheckStatus.WARN -> StatusLevel.Warning
    else -> StatusLevel.Neutral
}

/** A failed TLS check that is really a pin-able trust decision (untrusted self-signed / name mismatch). */
private fun isTrustDecision(c: CheckResult): Boolean =
    c.status == CheckStatus.FAIL &&
        (c.classification == TlsFailureClass.PKIX_PATH || c.classification == TlsFailureClass.HOSTNAME_MISMATCH)

/** Badge token: a pin-able trust decision reads "REVIEW" (an amber action), not the raw red "FAIL". */
private fun badgeText(c: CheckResult): String = if (isTrustDecision(c)) "REVIEW" else c.status.toString()

@Composable
private fun DiagnoseDialog(appState: AppState, d: AppDialog.Diagnose) {
    val diag = d.diagnosis
    ModalOverlay(
        title = diag.title,
        onDismiss = appState::dismissDialog,
        actions = {
            // The safe, spec-defined fixes — never a verify-weakening option.
            diag.actions.forEach { action -> FixButton(appState, d.connectionId, action) }
            GestaltButton("Close", style = ButtonStyle.Ghost, onClick = appState::dismissDialog)
        },
    ) {
        InlineNote(severityFor(diag), diag.explanation)
        GestaltText(diag.nextStep, role = TextRole.Body)
        if (appState.get(d.connectionId)?.isPlainHttp == true) {
            InlineNote(StatusLevel.Warning, "Note: this connection has no transport security (plain http).")
        }
    }
}

@Composable
private fun FixButton(appState: AppState, connectionId: String, action: FixAction) {
    when (action) {
        FixAction.ReviewAndPinCert -> GestaltButton("Review & pin certificate", onClick = { appState.beginTrustPrompt(connectionId) })
        FixAction.EditConnection -> GestaltButton("Open connection", style = ButtonStyle.Outlined, onClick = {
            appState.dismissDialog(); appState.route = Route.Edit(connectionId)
        })
        FixAction.ReviewTrustPosture -> GestaltButton("Review trust settings", style = ButtonStyle.Outlined, onClick = {
            appState.dismissDialog(); appState.route = Route.Trust
        })
        FixAction.TrustPublisher -> GestaltButton("Trust publisher & launch", onClick = {
            appState.dismissDialog()
            if (appState.trustPendingPublishers(connectionId) > 0) appState.launch(connectionId)
        })
        FixAction.ViewLogs -> GestaltButton("View logs", style = ButtonStyle.Ghost, onClick = appState::openLogsFolder)
        FixAction.None -> Unit
    }
}

private fun severityFor(diag: Diagnosis): StatusLevel = when (diag.kind) {
    FailureKind.DigestMismatch, FailureKind.SignatureUntrusted, FailureKind.ServerCertUntrusted -> StatusLevel.Error
    FailureKind.Unknown -> StatusLevel.Neutral
    else -> StatusLevel.Warning
}

@Composable
private fun TrustPinDialog(appState: AppState, d: AppDialog.TrustPin) {
    ModalOverlay(
        title = "Trust this server certificate?",
        onDismiss = appState::dismissDialog,
        actions = {
            GestaltButton("Trust & pin this certificate", onClick = { appState.confirmTrustPin(d.connectionId, d.cert) })
            GestaltButton("Cancel", style = ButtonStyle.Ghost, onClick = appState::dismissDialog)
        },
    ) {
        GestaltText(
            "Review the certificate presented by ${d.connectionName}. Pinning records the exact " +
                "certificate so future connections must present the same one. This decision is written to the audit log.",
            role = TextRole.Body,
        )
        CertDetailsView(d.details)
        val replaces = d.replacesPin
        when {
            replaces == null -> Unit
            replaces == d.details.fingerprintPlain ->
                InlineNote(StatusLevel.Success, "This matches the certificate already pinned to this connection.")
            else ->
                InlineNote(StatusLevel.Error, "This REPLACES a different certificate you previously pinned. Only continue if you expected the server's certificate to change.")
        }
    }
}

@Composable
private fun CertViewDialog(appState: AppState, d: AppDialog.CertView) {
    ModalOverlay(
        title = "Server certificate — ${d.connectionName}",
        onDismiss = appState::dismissDialog,
        actions = {
            if (d.pinMatches != true) {
                GestaltButton("Re-pin…", style = ButtonStyle.Outlined, onClick = { appState.beginTrustPrompt(d.connectionId) })
            }
            GestaltButton("Close", style = ButtonStyle.Ghost, onClick = appState::dismissDialog)
        },
    ) {
        CertDetailsView(d.details)
        when (d.pinMatches) {
            null -> InlineNote(StatusLevel.Neutral, "This connection has no pinned certificate.")
            true -> InlineNote(StatusLevel.Success, "The live certificate matches the pinned fingerprint.")
            false -> InlineNote(StatusLevel.Error, "The live certificate does NOT match the pinned fingerprint. The server may have rotated its certificate — or this could indicate interception. Re-pin only if you expected the change.")
        }
    }
}
