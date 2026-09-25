package com.vortex.timelock

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * ============================================================================
 *  Screen Time Locker — "Give Device Admin Permission" step (Kotlin migration)
 * ============================================================================
 *
 *  A self-contained onboarding card. The grant is FORCE-PROVISIONED through
 *  Shizuku only — the operator is NEVER bounced to the Android system Settings
 *  (there is no `DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN` intent, no
 *  `Settings.ACTION_SECURITY_SETTINGS`). Everything runs as shell/root via the
 *  Shizuku binder, exactly like the ADB command the card prints:
 *
 *   <ol>
 *     <li><b>Option 1 — Manual ADB fallback.</b> A read-only, selectable
 *         monospace text box holding the exact `adb shell dpm set-device-owner`
 *         line (built from [ShizukuShell.deviceOwnerCommand], so it can never
 *         drift from the command the app actually runs). There is deliberately
 *         NO button here — it is the PC/cable path.</li>
 *     <li><b>Option 2 — Approve via Shizuku.</b> One button that connects to
 *         the running Shizuku service and runs the chain sequentially:
 *         `pm list users` → `pm remove-user &lt;id&gt;` for every non-zero user →
 *         `dpm set-device-owner &lt;pkg&gt;/.TimeLockAdmin`.</li>
 *   </ol>
 *
 *  Button contract ([approveByShizuku]):
 *   <ol>
 *     <li>check + request the Shizuku application permission;</li>
 *     <li>once authorised, run the multi-user purge (`pm list users` /
 *         `pm remove-user`) and the Device-Owner grant (`dpm
 *         set-device-owner`) inside one Shizuku shell session;</li>
 *     <li>only when the shell returns exit code 0, show the success toast and
 *         move on to the setup screen (via [onOwnerGranted]).</li>
 *   </ol>
 *
 *  Threading: this class only touches views on the main thread. Every blocking
 *  binder call is delegated to [ShizukuShell], which runs the whole chain on its
 *  own single worker thread and republishes the result to the main looper. The
 *  per-app permission dialog is asynchronous, so the permission result listener
 *  (and the binder received/dead listeners) are registered by [attach] and torn
 *  down by [detach] to avoid leaking into a stopped Activity.
 *
 *  @param activity owning Activity, used for dialogs / toasts / lifecycle guards.
 *  @param ui       shared design tokens (resolved once by the caller).
 *  @param onOwnerGranted invoked on the main thread AFTER a successful shell run
 *         (exit code 0); the host uses it to navigate to the setup screen.
 */
internal class DeviceAdminSection(
    private val activity: Activity,
    private val ui: Ui,
    private val onOwnerGranted: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "TL.DeviceAdminSection"
    }

    /** The root card. Add this to the permission-steps container. */
    val view: View

    // ---- views ----
    private lateinit var shizukuBtn: Button
    private lateinit var shizukuState: TextView
    private lateinit var ownerState: TextView
    private lateinit var outputBlock: TextView

    // ---- listeners (held so detach() can remove exactly what attach() added) ----
    private val permissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            // Fires on the main thread; a grant means we can run immediately.
            refreshAll()
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runProvision()
            }
        }
    }

    private val binderReceivedListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            refreshAll()
        }
    }

    private val binderDeadListener = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() {
            refreshAll()
        }
    }

    private var attached = false

    init {
        view = build()
        refreshAll()
    }

    // ============================================================ lifecycle

    /**
     * Registers the Shizuku listeners and repaints the live state. Call from
     * [Activity.onResume] (or after the card is first shown). Idempotent.
     */
    fun attach() {
        if (attached) return
        attached = true
        ShizukuShell.addPermissionListener(permissionListener)
        ShizukuShell.addBinderReceivedListener(binderReceivedListener)
        ShizukuShell.addBinderDeadListener(binderDeadListener)
        refreshAll()
    }

    /** Removes every listener registered by [attach]. Call from [Activity.onPause]. */
    fun detach() {
        if (!attached) return
        attached = false
        ShizukuShell.removePermissionListener(permissionListener)
        ShizukuShell.removeBinderReceivedListener(binderReceivedListener)
        ShizukuShell.removeBinderDeadListener(binderDeadListener)
    }

    // ============================================================ build

    private fun build(): View {
        val c = card()

        c.addView(title(activity.getString(R.string.dap_title)))
        c.addView(body(activity.getString(R.string.dap_intro)))

        // ---- Option 1: manual ADB fallback (command box only) ----
        c.addView(label(activity.getString(R.string.dap_option1_label)))
        c.addView(monoBlock("adb shell " + ShizukuShell.deviceOwnerCommand(activity)))
        c.addView(note(activity.getString(R.string.dap_option1_note)))

        c.addView(divider())

        // ---- Option 2: approve via Shizuku ----
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val opt2 = TextView(activity)
        opt2.setText(R.string.dap_option2_title)
        opt2.setTextColor(ui.text)
        opt2.setTextSize(15f)
        opt2.typeface = Ui.bold()
        opt2.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        row.addView(opt2)

        shizukuState = TextView(activity)
        shizukuState.setText("\u2014")
        shizukuState.setTextSize(11.5f)
        shizukuState.typeface = Ui.bold()
        shizukuState.setTextColor(ui.textFaint)
        shizukuState.letterSpacing = 0.08f
        row.addView(shizukuState)
        c.addView(row)

        c.addView(note(activity.getString(R.string.dap_option2_note)))

        shizukuBtn = actionButton(activity.getString(R.string.dap_approve_button), ui.accent, ui.onAccent, 14f)
        shizukuBtn.setOnClickListener { approveByShizuku() }
        c.addView(shizukuBtn)

        outputBlock = TextView(activity)
        outputBlock.setTextColor(ui.textDim)
        outputBlock.setTextSize(12f)
        outputBlock.typeface = Ui.mono()
        outputBlock.setLineSpacing(dp(3f).toFloat(), 1f)
        outputBlock.background = ui.rounded(ui.field, 12f, ui.border, 1f)
        outputBlock.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        val olp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        olp.topMargin = dp(12f)
        outputBlock.layoutParams = olp
        outputBlock.visibility = View.GONE
        c.addView(outputBlock)

        c.addView(divider())

        // ---- live Device Owner status ----
        val ownerRow = LinearLayout(activity)
        ownerRow.orientation = LinearLayout.HORIZONTAL
        ownerRow.gravity = Gravity.CENTER_VERTICAL

        val ownerLabel = TextView(activity)
        ownerLabel.setText(R.string.dap_owner_state_label)
        ownerLabel.setTextColor(ui.text)
        ownerLabel.setTextSize(15f)
        ownerLabel.typeface = Ui.bold()
        ownerLabel.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        ownerRow.addView(ownerLabel)

        ownerState = TextView(activity)
        ownerState.setText("\u2014")
        ownerState.setTextSize(11.5f)
        ownerState.typeface = Ui.bold()
        ownerState.setTextColor(ui.textFaint)
        ownerState.letterSpacing = 0.08f
        ownerRow.addView(ownerState)
        c.addView(ownerRow)

        return c
    }

    // ============================================================ actions

    /**
     * "Approve via Shizuku" click handler — the whole force-provision, in order:
     *
     *  1. Shizuku shell access: make sure the manager is installed, the service
     *     is running and this app holds Shizuku's runtime permission. A missing
     *     grant is REQUESTED here; because that dialog is async we return and
     *     let [permissionListener] resume the run once the user answers ALLOW.
     *  2. Multi-user purge: `pm list users` through the Shizuku shell, parse the
     *     output, then `pm remove-user <id>` for every id that is not 0.
     *  3. Device-Owner provisioning: `dpm set-device-owner <pkg>/.TimeLockAdmin`
     *     in the same Shizuku shell session.
     *
     * No system Settings screen is ever launched — the commands execute with
     * shell privileges through the Shizuku binder (see [ShizukuShell]).
     */
    private fun approveByShizuku() {
        // ---- 1. Shizuku shell access -----------------------------------------
        if (!ShizukuShell.isManagerInstalled(activity)) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dap_dlg_no_app_title)
                .setMessage(R.string.dap_dlg_no_app_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (!ShizukuShell.isServiceAlive()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dap_dlg_not_running_title)
                .setMessage(R.string.dap_dlg_not_running_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (!ShizukuShell.isPermissionGranted()) {
            toast(activity.getString(R.string.dap_toast_allow))
            ShizukuShell.requestPermission()
            return
        }

        // ---- 2 + 3. purge users, then provision Device Owner ------------------
        runProvision()
    }

    /** Kicks the blocking chain onto [ShizukuShell]'s worker thread. */
    private fun runProvision() {
        shizukuBtn.isEnabled = false
        shizukuBtn.setText(R.string.dap_working)
        showOutput("Running pm list users \u2026")

        val accepted = ShizukuShell.provisionAsync(activity) { rep ->
            onProvisionDone(rep)
        }
        if (!accepted) {
            // A chain was already in flight (AtomicBoolean gate); restore the UI.
            shizukuBtn.isEnabled = true
            shizukuBtn.setText(R.string.dap_approve_button)
            toast(activity.getString(R.string.dap_toast_busy))
        }
    }

    /** Main-thread callback with the fully-populated provisioning report. */
    private fun onProvisionDone(rep: ShizukuShell.ProvisionReport) {
        if (activity.isFinishing) return
        if (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed) return

        shizukuBtn.isEnabled = true
        shizukuBtn.setText(R.string.dap_approve_button)

        val sb = StringBuilder()
        sb.append("$ pm list users\n").append(rep.usersOutput).append('\n')
        sb.append('\n').append(rep.removeOutput).append('\n')
        sb.append("\n$ ").append(rep.adbCommand).append('\n').append(rep.ownerOutput)
        showOutput(sb.toString())

        refreshAll()

        if (rep.success) {
            // rep.success is asserted ONLY when the shell returned exit code 0
            // for `dpm set-device-owner`. Celebrate with a toast, then hand off
            // to the setup screen. No system Settings screen is involved.
            toast(activity.getString(R.string.dap_toast_granted))
            onOwnerGranted?.invoke()
        } else {
            val line = rep.failure
                ?: ShizukuShell.formatExecutionError(activity.getString(R.string.dap_dlg_failed_body))
            AlertDialog.Builder(activity)
                .setTitle(R.string.dap_dlg_failed_title)
                .setMessage(line)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showOutput(s: String) {
        outputBlock.text = s
        outputBlock.visibility = View.VISIBLE
    }

    // ============================================================ state

    private fun refreshAll() {
        refreshShizuku()
        refreshOwner()
    }

    private fun refreshShizuku() {
        val label: String
        val colour: Int
        if (!ShizukuShell.isManagerInstalled(activity)) {
            label = activity.getString(R.string.dap_state_not_installed); colour = ui.textFaint
        } else if (!ShizukuShell.isServiceAlive()) {
            label = activity.getString(R.string.dap_state_not_running); colour = ui.warn
        } else if (!ShizukuShell.isPermissionGranted()) {
            label = activity.getString(R.string.dap_state_permission); colour = ui.warn
        } else {
            label = activity.getString(R.string.dap_state_ready); colour = ui.ok
        }
        shizukuState.text = label
        shizukuState.setTextColor(colour)
    }

    private fun refreshOwner() {
        val owner = Engine.isDeviceOwner(activity)
        val admin = Engine.isAdminActive(activity)
        when {
            owner -> {
                ownerState.setText(R.string.dap_state_owner)
                ownerState.setTextColor(ui.ok)
            }
            admin -> {
                ownerState.setText(R.string.dap_state_admin_only)
                ownerState.setTextColor(ui.warn)
            }
            else -> {
                ownerState.setText(R.string.dap_state_not_granted)
                ownerState.setTextColor(ui.dangerText)
            }
        }
    }

    // ============================================================ small view factory

    private fun card(): LinearLayout {
        val c = LinearLayout(activity)
        c.orientation = LinearLayout.VERTICAL
        c.background = ui.rounded(ui.surface, 18f, ui.border, 1.5f)
        c.setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(16f)
        c.layoutParams = lp
        return c
    }

    private fun title(s: String): TextView {
        val t = TextView(activity)
        t.text = s
        t.setTextColor(ui.text)
        t.setTextSize(17f)
        t.typeface = Ui.bold()
        return t
    }

    private fun body(s: String): TextView {
        val t = TextView(activity)
        t.text = s
        t.setTextColor(ui.textDim)
        t.setTextSize(14f)
        t.setLineSpacing(dp(4f).toFloat(), 1f)
        t.setPadding(0, dp(8f), 0, dp(4f))
        return t
    }

    private fun label(s: String): TextView {
        val t = TextView(activity)
        t.text = s
        t.setTextColor(ui.textFaint)
        t.setTextSize(11.5f)
        t.typeface = Ui.bold()
        t.letterSpacing = 0.12f
        t.setPadding(0, dp(14f), 0, dp(6f))
        return t
    }

    private fun note(s: String): TextView {
        val t = TextView(activity)
        t.text = s
        t.setTextColor(ui.textFaint)
        t.setTextSize(12.5f)
        t.setLineSpacing(dp(3f).toFloat(), 1f)
        t.setPadding(0, dp(10f), 0, 0)
        return t
    }

    /** Monospaced, selectable command block — matches the Java admin card. */
    private fun monoBlock(s: String): TextView {
        val t = TextView(activity)
        t.text = s
        t.setTextColor(ui.text)
        t.setTextSize(13f)
        t.typeface = Ui.mono()
        t.setTextIsSelectable(true)
        t.background = ui.rounded(ui.field, 12f, ui.border, 1f)
        t.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(4f)
        t.layoutParams = lp
        return t
    }

    private fun divider(): View {
        val v = View(activity)
        v.setBackgroundColor(ui.border)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f))
        lp.topMargin = dp(16f)
        lp.bottomMargin = dp(2f)
        v.layoutParams = lp
        return v
    }

    private fun actionButton(text: String, bg: Int, fg: Int, sizeSp: Float): Button {
        val b = Button(activity)
        b.text = text
        b.isAllCaps = false
        b.setTextSize(sizeSp)
        b.setTextColor(fg)
        b.typeface = Ui.bold()
        b.background = ui.rounded(bg, 14f)
        b.setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(12f)
        b.layoutParams = lp
        b.minWidth = 0; b.minimumWidth = 0
        b.minHeight = 0; b.minimumHeight = 0
        b.stateListAnimator = null
        return b
    }

    private fun dp(v: Float): Int = ui.dp(v)

    private fun toast(s: String) {
        try {
            android.widget.Toast.makeText(activity, s, android.widget.Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // A toast can race with a finishing Activity; harmless.
        }
    }
}
