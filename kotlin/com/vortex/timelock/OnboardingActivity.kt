package com.vortex.timelock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ============================================================================
 *  Screen Time Locker — initial onboarding (first screen)
 * ============================================================================
 *
 *  Layout: [activity_onboarding.xml].
 *
 *  This is the very first surface of the app. It exists to make the hard
 *  pre-requisite impossible to miss and to give the permission walkthrough a
 *  single, stable home:
 *
 *  <ol>
 *    <li><b>Warning banner.</b> Pinned to the very top and rendered verbatim
 *        from [R.string.onboarding_warning_banner]. The message is a hard
 *        requirement — the operator must factory reset, or strip every account,
 *        disable auto-sync and reboot — because Device Owner can only be
 *        provisioned on an account-free, freshly-booted device. The banner text
 *        is never reworded or truncated here; it is bound straight from the
 *        resource.</li>
 *    <li><b>Permission-steps container.</b> [R.id.permissionStepsContainer] is
 *        the placeholder the upcoming per-permission rows are injected into via
 *        [addStep]. The initial placeholder child is removed on first injection,
 *        so the container is always in a clean, ready state.</li>
 *  </ol>
 *
 *  The screen is deliberately thin: it owns presentation and the step slot only.
 *  It mounts the "Give Device Admin Permission" card ([DeviceAdminSection])
 *  inline, which force-provisions Device Owner through Shizuku (never by opening
 *  system Settings). CONTINUE hands over to [PermissionActivity] for the
 *  remaining per-permission approve buttons.
 */
class OnboardingActivity : Activity() {

    companion object {
        private const val TAG = "TL.Onboarding"
    }

    // ---- views ----
    private lateinit var stepsContainer: LinearLayout
    private lateinit var stepsPlaceholder: TextView
    private lateinit var continueButton: Button

    // ---- device-admin step (Kotlin migration) ----
    private var adminSection: DeviceAdminSection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        stepsContainer = findViewById(R.id.permissionStepsContainer)
        stepsPlaceholder = findViewById(R.id.permissionStepsPlaceholder)
        continueButton = findViewById(R.id.btnContinue)

        // Bind the banner text straight from the resource: the required wording
        // is a single string and must render exactly as specified.
        findViewById<TextView>(R.id.warningBannerText).text =
            getString(R.string.onboarding_warning_banner)

        renderPermissionSteps()
        mountDeviceAdminSection()
        continueButton.setOnClickListener { onContinue() }
    }

    /**
     * Builds and injects the "Give Device Admin Permission" card into the shared
     * step container. The card owns its own Shizuku listeners, which it registers
     * in [onResume] and releases in [onPause] so its live state stays fresh while
     * the screen is visible and never leaks into a stopped Activity.
     */
    private fun mountDeviceAdminSection() {
        val section = DeviceAdminSection(
            activity = this,
            ui = Ui(this),
            // Fired only after the Shizuku shell returns exit code 0 for
            // `dpm set-device-owner`. The card has already shown the success
            // toast; here we hand the operator straight to the setup screen.
            onOwnerGranted = { openSetup() }
        )
        adminSection = section
        addStep(section.view)
    }

    override fun onResume() {
        super.onResume()
        adminSection?.attach()
    }

    override fun onPause() {
        adminSection?.detach()
        super.onPause()
    }

    /**
     * Renders the (currently placeholder) list of permission steps. Nothing is
     * requested here — this only paints the container so the screen is never
     * blank while the step rows are being built.
     */
    private fun renderPermissionSteps() {
        stepsContainer.removeAllViews()
        if (stepsPlaceholder.parent !== stepsContainer) {
            stepsContainer.addView(stepsPlaceholder)
        }
    }

    /**
     * Adds a single permission-step row to the shared container, removing the
     * empty placeholder on first use. Call once per upcoming permission step.
     */
    fun addStep(view: View) {
        if (stepsPlaceholder.parent === stepsContainer) {
            stepsContainer.removeView(stepsPlaceholder)
        }
        stepsContainer.addView(view)
    }

    /** Continues into the existing permission onboarding / setup console. */
    private fun onContinue() {
        try {
            startActivity(Intent(this, PermissionActivity::class.java))
        } catch (t: Throwable) {
            // Never let a missing hand-off surface trap the operator on a dead end.
            continueButton.isEnabled = true
        }
    }

    /**
     * Moves to the setup screen after Device Owner has been force-provisioned via
     * Shizuku. Mirrors [PermissionActivity.openSetup]: prefer [MainSetupActivity]
     * and fall back to the configuration console so the operator is never left on
     * a dead end. No system Settings screen is ever opened along the way.
     */
    private fun openSetup() {
        try {
            startActivity(Intent(this, MainSetupActivity::class.java))
            return
        } catch (t: Throwable) {
            // fall through to the console
        }
        try {
            startActivity(Intent(this, SetupActivity::class.java))
        } catch (t: Throwable) {
            // Both hand-offs unavailable; the CONTINUE button remains live.
        }
    }
}
