/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import com.gestalt.fx.model.LauncherModel
import com.gestalt.launcher.gate.TrustedSigner
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.launcher.tls.CertTrustStatus
import com.gestalt.launcher.tls.TrustedCert
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Server certificates the launcher trusts" — every entry of the trusted-certificate store and, as
 * review items of the same shape, the jar publishers awaiting a decision. All / Needs review, Trust /
 * Reject on a pending row, Remove elsewhere, and a row expands to subject, issuer, validity and the
 * full SHA-256.
 */
class CertificatesDialog(private val model: LauncherModel, private val modal: ModalHost, private val onChanged: () -> Unit) {

    private var reviewOnly = false
    private var openKey: String? = null
    private var current: Connection? = null
    private val listBox = VBox().apply { styleClass += "cert-list" }
    private val segHolder = HBox()

    fun show(current: Connection?) {
        this.current = current
        reviewOnly = false
        openKey = null
        val card = VBox().apply {
            styleClass += "modal-card"
            children += HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label("Certificates").apply { styleClass += "card-title" }
                children += Controls.spacer()
                children += Controls.iconButton("x", "Close", 16.0) { modal.close() }.apply { minWidth = 28.0; prefWidth = 28.0; maxWidth = 28.0; minHeight = 28.0; prefHeight = 28.0; maxHeight = 28.0 }
            }
            children += Label("Server certificates the launcher trusts. Unknown certificates prompt on launch unless auto-approve is on for that connection. Jar publishers awaiting review are listed here too.").apply {
                styleClass += "modal-body"; isWrapText = true; VBox.setMargin(this, Insets(4.0, 0.0, 0.0, 0.0))
            }
            children += segHolder.apply { VBox.setMargin(this, Insets(14.0, 0.0, 0.0, 0.0)) }
            children += ScrollPane(listBox).apply {
                styleClass += "detail-scroll"
                isFitToWidth = true
                hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                prefViewportHeight = 340.0
                minHeight = 120.0
                VBox.setMargin(this, Insets(10.0, 0.0, 0.0, 0.0))
                VBox.setVgrow(this, Priority.ALWAYS)
            }
            children += HBox().apply {
                styleClass += "modal-actions"
                padding = Insets(16.0, 0.0, 0.0, 0.0)
                children += Controls.button("Done") { modal.close() }
            }
        }
        rebuild()
        modal.show(card, 660.0, escape = { modal.close() }, enter = { modal.close() })
    }

    private sealed class Row {
        abstract val key: String
        abstract val rank: Int
        abstract val isCurrent: Boolean
        data class Cert(val entry: TrustedCert, override val isCurrent: Boolean) : Row() {
            override val key get() = "cert:${entry.sha256}"
            override val rank get() = when { entry.status == CertTrustStatus.PENDING -> 0; entry.status == CertTrustStatus.REJECTED -> 3; entry.isExpired() -> 2; else -> 2 }
        }
        data class Signer(val entry: TrustedSigner) : Row() {
            override val key get() = "signer:${entry.sha256}"
            override val rank get() = when (entry.status) { CertTrustStatus.PENDING -> 0; CertTrustStatus.REJECTED -> 3; CertTrustStatus.TRUSTED -> 2 }
            override val isCurrent get() = false
        }
    }

    private fun rows(): List<Row> {
        val c = current
        val hostPort = c?.hostPort
        val certs = model.trustedCerts.list().map { e -> Row.Cert(e, isCurrent = (hostPort != null && hostPort in e.hosts) || (c != null && c.id in e.connectionIds)) }
        val signers = model.trustedSigners.list().map { Row.Signer(it) }
        val all = certs + signers
        val shown = if (reviewOnly) all.filter { it.rank == 0 } else all
        return shown.sortedWith(compareBy<Row> { if (it.isCurrent) 0 else 1 }.thenBy { it.rank }.thenBy { title(it).lowercase() })
    }

    private fun title(row: Row): String = when (row) {
        is Row.Cert -> row.entry.hosts.firstOrNull() ?: cn(row.entry.subject) ?: row.entry.sha256.take(16)
        is Row.Signer -> row.entry.displayName
    }

    private fun rebuild() {
        val all = model.trustedCerts.list().size + model.trustedSigners.list().size
        // What needs a decision: the undecided. An expired-but-trusted certificate is shown as Expired, not
        // counted here — there is no decision that clears it (the server has to renew), so it must not be a count that never goes away.
        val needs = model.trustedCerts.pending().size + model.trustedSigners.pending().size
        val seg = Controls.Segmented(listOf(false to "All $all", true to "Needs review $needs")) { v -> reviewOnly = v; rebuild() }
        seg.value = reviewOnly
        segHolder.children.setAll(seg)

        listBox.children.clear()
        val list = rows()
        if (list.isEmpty()) {
            listBox.children += Label(if (reviewOnly) "Nothing needs review." else "No certificates yet — the first https launch records one.").apply { styleClass += "cert-empty"; maxWidth = Double.MAX_VALUE; alignment = Pos.CENTER }
            return
        }
        list.forEachIndexed { i, row ->
            val node = VBox().apply { if (i > 0) styleClass += "cert-row-sep" }
            node.children += header(row)
            if (openKey == row.key) node.children += details(row)
            listBox.children += node
        }
    }

    private fun header(row: Row): HBox {
        val (glyph, color, label) = status(row)
        val open = openKey == row.key
        val head = HBox().apply { styleClass += "cert-row"; if (open) styleClass += "open" }
        head.children += Icons.glyph(glyph, 16.0, color)
        val titleRow = HBox(6.0).apply {
            alignment = Pos.CENTER_LEFT
            children += Label(title(row)).apply { styleClass += "cert-host"; minWidth = 0.0 }
            if (row.isCurrent) children += Label("This connection").apply { styleClass += "chip" }
        }
        val sub = Label(subtitle(row)).apply { styleClass.addAll("small", "muted"); minWidth = 0.0 }
        head.children += VBox(2.0, titleRow, sub).apply { minWidth = 0.0; HBox.setHgrow(this, Priority.ALWAYS) }
        head.children += VBox(2.0).apply {
            alignment = Pos.CENTER_RIGHT
            minWidth = 118.0; prefWidth = 118.0
            children += Label(label).apply { styleClass.addAll("small", "strong"); style = "-fx-text-fill: $color;" }
            children += Label(expiry(row)).apply { styleClass += "caption"; styleClass += "muted" }
        }
        head.children += HBox(6.0).apply {
            alignment = Pos.CENTER_RIGHT
            minWidth = 196.0; prefWidth = 196.0
            // Every row can be removed — an undecided one too, so nothing awaiting review is ever stuck there.
            when (row) {
                is Row.Cert -> {
                    children += Controls.button("Remove", Controls.ButtonKind.Ghost, small = true) { model.trustedCerts.remove(row.entry.sha256); changed() }
                    if (row.entry.status == CertTrustStatus.PENDING) {
                        children += Controls.button("Reject", Controls.ButtonKind.Ghost, small = true) { model.trustedCerts.rejectListed(row.entry.sha256); changed() }
                        children += Controls.button("Trust", Controls.ButtonKind.Primary, small = true) { model.trustedCerts.trustListed(row.entry.sha256); changed() }
                    }
                }
                is Row.Signer -> {
                    children += Controls.button("Remove", Controls.ButtonKind.Ghost, small = true) { model.trustedSigners.remove(row.entry.sha256); changed() }
                    if (row.entry.status == CertTrustStatus.PENDING) {
                        children += Controls.button("Reject", Controls.ButtonKind.Ghost, small = true) { model.trustedSigners.reject(row.entry.sha256); changed() }
                        children += Controls.button("Trust", Controls.ButtonKind.Primary, small = true) { model.trustedSigners.trust(row.entry.sha256); changed() }
                    }
                }
            }
            setOnMouseClicked { it.consume() }
        }
        head.children += StackPane(Icons.glyph(if (open) "chevron-up" else "chevron-down", 14.0))
        head.setOnMouseClicked { openKey = if (open) null else row.key; rebuild() }
        return head
    }

    private fun details(row: Row): GridPane = GridPane().apply {
        styleClass += "cert-detail"
        columnConstraints.setAll(
            javafx.scene.layout.ColumnConstraints().apply { minWidth = 72.0; prefWidth = 72.0 },
            javafx.scene.layout.ColumnConstraints().apply { hgrow = Priority.ALWAYS; minWidth = 0.0 },
        )
        val (subject, issuer, valid, sha, extra) = when (row) {
            is Row.Cert -> Five(row.entry.subject ?: "—", row.entry.issuer ?: "—", validity(row.entry.notBefore, row.entry.notAfter), row.entry.sha256, "Seen at " + (row.entry.hosts.ifEmpty { listOf("—") }).joinToString(", ") + if (row.entry.autoApproved) " · auto-approved" else "")
            is Row.Signer -> Five(row.entry.subject, row.entry.issuer, validity(row.entry.notBefore, row.entry.notAfter), row.entry.sha256, "Signed " + row.entry.artifacts.ifEmpty { listOf("—") }.joinToString(", "))
        }
        var r = 0
        fun line(k: String, v: String, mono: Boolean = false) {
            add(Label(k).apply { styleClass += "muted" }, 0, r)
            add(Label(v).apply { if (mono) styleClass += "mono"; isWrapText = true; maxWidth = Double.MAX_VALUE }, 1, r)
            r++
        }
        line("Subject", subject, mono = true)
        line("Issuer", issuer)
        line("Valid", valid)
        line("SHA-256", CertFingerprint.normalize(sha).uppercase().chunked(2).joinToString(":"), mono = true)
        line(if (row is Row.Cert) "Where" else "Jars", extra)
    }

    private data class Five(val a: String, val b: String, val c: String, val d: String, val e: String)

    private fun status(row: Row): Triple<String, String, String> = when (row) {
        is Row.Cert -> when {
            row.entry.status == CertTrustStatus.PENDING -> Triple("shield-alert", "-warn", "Needs review")
            row.entry.status == CertTrustStatus.REJECTED -> Triple("shield-x", "-danger", "Rejected")
            row.entry.isExpired() -> Triple("shield-x", "-danger", "Expired")
            else -> Triple("shield-check", "-ok", "Trusted")
        }
        is Row.Signer -> when (row.entry.status) {
            CertTrustStatus.PENDING -> Triple("shield-alert", "-warn", "Needs review")
            CertTrustStatus.REJECTED -> Triple("shield-x", "-danger", "Rejected")
            CertTrustStatus.TRUSTED -> Triple("shield-check", "-ok", "Trusted publisher")
        }
    }

    private fun subtitle(row: Row): String = when (row) {
        is Row.Cert -> listOfNotNull(connectionNames(row.entry), cn(row.entry.issuer)).joinToString(" · ").ifEmpty { "Server certificate" }
        is Row.Signer -> "Jar publisher · " + (cn(row.entry.issuer) ?: "self-signed")
    }

    private fun connectionNames(entry: TrustedCert): String? =
        entry.connectionIds.mapNotNull { id -> model.connections.firstOrNull { it.id == id }?.name }.takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun expiry(row: Row): String {
        val notAfter = when (row) { is Row.Cert -> row.entry.notAfter; is Row.Signer -> row.entry.notAfter }
        val at = notAfter?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
        val date = at.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        return if (at.isBefore(Instant.now())) "Expired $date" else "Expires $date"
    }

    private fun validity(from: String?, to: String?): String {
        fun f(s: String?) = s?.let { runCatching { Instant.parse(it) }.getOrNull() }?.atZone(ZoneId.systemDefault())?.toLocalDate()?.format(DateTimeFormatter.ofPattern("MMM d, yyyy")) ?: "—"
        return f(from) + " – " + f(to)
    }

    private fun cn(dn: String?): String? = dn?.let { Regex("(?:^|,)\\s*CN=([^,]+)").find(it)?.groupValues?.get(1)?.trim() }

    private fun changed() {
        onChanged()
        rebuild()
    }
}
