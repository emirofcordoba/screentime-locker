package com.vortex.timelock

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================================================
 *  Screen Time Locker — thread-safe Shizuku transport (Kotlin migration)
 * ============================================================================
 *
 *  The ONE place the Kotlin UI talks to Shizuku. It wraps the bundled Shizuku
 *  13.1.5 client (`rikka.shizuku.*`) and its binder AIDL
 *  (`moe.shizuku.server.*`) so no View, Fragment or Activity ever touches a
 *  binder directly.
 *
 *  Thread-safety contract:
 *   - [run] and [provisionDeviceOwner] perform BLOCKING binder + process I/O and
 *     must never be called on the main thread.
 *   - [provisionAsync] is the supported entry point: it hands the whole chain to
 *     a single-thread executor, so commands always run strictly in order and at
 *     most one provisioning run is ever in flight (an [AtomicBoolean] gate).
 *   - Results are republished to the main looper via a [Handler], so the caller
 *     may touch views in the callback without extra postings.
 *   - Every binder call is individually guarded: a dead binder, a revoked
 *     permission or any [android.os.RemoteException] degrades to a readable
 *     message instead of a crash.
 *
 *  It is intentionally self-contained (namespaced as *Shell*, not *Bridge*, so
 *  it can coexist with the legacy Java [ShizukuBridge] during the migration).
 */
object ShizukuShell {

    const val TAG = "TL.ShizukuShell"

    /** The Shizuku manager package (the app the user installs and starts). */
    const val MANAGER_PKG = "moe.shizuku.privileged.api"

    /** Request code for Shizuku's own per-app permission dialog. */
    const val REQ_PERMISSION = 8465

    /** Main-thread poster used to deliver every result back to the UI safely. */
    private val main = Handler(Looper.getMainLooper())

    /** Single worker thread: every shell command runs here, strictly in order. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shizuku-provision").apply { isDaemon = true }
    }

    /** Gate that guarantees only one provisioning chain runs at a time. */
    private val busy = AtomicBoolean(false)

    // ============================================================ availability

    /** True when the Shizuku manager app is installed on the device. */
    fun isManagerInstalled(c: Context?): Boolean {
        if (c == null) return false
        return try {
            c.packageManager.getPackageInfo(MANAGER_PKG, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** True when the Shizuku service binder is reachable (service up + bound). */
    fun isServiceAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** True when this app has been granted Shizuku's runtime permission. */
    fun isPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** Ask Shizuku's manager to show its per-app permission dialog. */
    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQ_PERMISSION)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission", t)
        }
    }

    /**
     * Collapse any execution failure into a SINGLE-LINE, exception-shaped
     * string instead of a bare numeric error code. All newlines / carriage
     * returns become spaces and repeated whitespace is squeezed, so the result
     * is always exactly one line formatted as `Error: <detail>`.
     */
    fun formatExecutionError(detail: String?): String {
        var one = (detail ?: "").replace('\r', ' ').replace('\n', ' ')
        one = one.replace(Regex("\\s{2,}"), " ").trim()
        if (one.isEmpty()) one = "unknown failure"
        return "Error: $one"
    }

    /** Human-readable Shizuku server version, or "" when unavailable. */
    fun version(): String = try {
        val v = Shizuku.getVersion()
        if (v <= 0) "" else "v$v"
    } catch (t: Throwable) {
        ""
    }

    /** True while a [provisionAsync] chain is executing on the worker thread. */
    fun isBusy(): Boolean = busy.get()

    // ============================================================ listeners

    /** Register a per-app permission result listener (safe no-op on failure). */
    fun addPermissionListener(l: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(l)
        } catch (t: Throwable) {
            Log.w(TAG, "addPermissionListener", t)
        }
    }

    /** Remove a previously registered permission result listener. */
    fun removePermissionListener(l: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(l)
        } catch (t: Throwable) {
            Log.w(TAG, "removePermissionListener", t)
        }
    }

    /** Register a sticky binder-received listener (fires immediately when alive). */
    fun addBinderReceivedListener(l: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.addBinderReceivedListenerSticky(l)
        } catch (t: Throwable) {
            Log.w(TAG, "addBinderReceivedListener", t)
        }
    }

    /** Remove a previously registered binder-received listener. */
    fun removeBinderReceivedListener(l: Shizuku.OnBinderReceivedListener) {
        try {
            Shizuku.removeBinderReceivedListener(l)
        } catch (t: Throwable) {
            Log.w(TAG, "removeBinderReceivedListener", t)
        }
    }

    /** Register a binder-death listener so the UI can drop back to "not running". */
    fun addBinderDeadListener(l: Shizuku.OnBinderDeadListener) {
        try {
            Shizuku.addBinderDeadListener(l)
        } catch (t: Throwable) {
            Log.w(TAG, "addBinderDeadListener", t)
        }
    }

    /** Remove a previously registered binder-death listener. */
    fun removeBinderDeadListener(l: Shizuku.OnBinderDeadListener) {
        try {
            Shizuku.removeBinderDeadListener(l)
        } catch (t: Throwable) {
            Log.w(TAG, "removeBinderDeadListener", t)
        }
    }

    // ============================================================ result model

    /** Outcome of one shell invocation. Fields are volatile: read from any thread. */
    class Exec {
        @Volatile var ok: Boolean = false      // started and exit code == 0
        @Volatile var exitCode: Int = -1
        @Volatile var output: String = ""      // combined stdout + stderr, trimmed
        @Volatile var error: String? = null    // transport failure (null when it ran)

        /** True when the process actually started (regardless of exit code). */
        fun ran(): Boolean = error == null
    }

    /**
     * Run one command through the Shizuku shell and capture its output.
     * BLOCKING — call off the main thread. Never throws: transport problems come
     * back as [Exec.error].
     */
    fun run(command: String): Exec {
        val r = Exec()
        try {
            if (!isServiceAlive()) {
                r.error = "Shizuku service is not running"
                return r
            }
            if (!isPermissionGranted()) {
                r.error = "Shizuku permission not granted"
                return r
            }
            val binder: IBinder? = Shizuku.getBinder()
            if (binder == null) {
                r.error = "Shizuku binder unavailable"
                return r
            }
            val svc: IShizukuService? = IShizukuService.Stub.asInterface(binder)
            if (svc == null) {
                r.error = "Shizuku service interface unavailable"
                return r
            }
            val p: IRemoteProcess? = svc.newProcess(arrayOf("sh", "-c", command), null, null)
            if (p == null) {
                r.error = "Shizuku refused to start the command"
                return r
            }
            val out = readFd(p.inputStream)
            val err = readFd(p.errorStream)
            val code = p.waitFor()
            r.exitCode = code
            r.ok = code == 0
            val sb = StringBuilder()
            if (!out.isNullOrBlank()) sb.append(out.trim())
            if (!err.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(err.trim())
            }
            r.output = sb.toString()
            return r
        } catch (t: Throwable) {
            Log.w(TAG, "run: $command", t)
            r.error = t.javaClass.simpleName + ": " + t.message
            return r
        }
    }

    /** Drain one pipe fully into a String, then release the descriptor. */
    private fun readFd(pfd: ParcelFileDescriptor?): String? {
        if (pfd == null) return null
        try {
            val stream = FileInputStream(pfd.fileDescriptor)
            val br = BufferedReader(InputStreamReader(stream, "UTF-8"))
            val sb = StringBuilder()
            while (true) {
                val line = br.readLine() ?: break
                sb.append(line).append('\n')
            }
            br.close()
            return sb.toString()
        } catch (t: Throwable) {
            return null
        } finally {
            try {
                pfd.close()
            } catch (ignored: Throwable) {
                // already released / dead binder — nothing actionable
            }
        }
    }

    // ============================================================ user parsing

    /** Matches the "UserInfo{0:..." prefix the platform prints per user. */
    private val USER_RE = Regex("""UserInfo\{(\d+):""")

    /** Every user id reported by `pm list users`, de-duplicated, in order. */
    fun parseUserIds(pmListUsersOutput: String?): List<Int> {
        if (pmListUsersOutput == null) return emptyList()
        val ids = LinkedHashSet<Int>()
        for (m in USER_RE.findAll(pmListUsersOutput)) {
            m.groupValues[1].toIntOrNull()?.let { ids.add(it) }
        }
        return ids.toList()
    }

    // ============================================================ the task

    /** Everything the Shizuku approval produced, for display on the screen. */
    class ProvisionReport {
        @Volatile var success: Boolean = false
        @Volatile var adbCommand: String = ""
        @Volatile var usersOutput: String = ""
        val removedUsers: MutableList<Int> = ArrayList()
        @Volatile var removeOutput: String = ""
        @Volatile var ownerOutput: String = ""
        @Volatile var failure: String? = null
    }

    /** The exact ADB command that grants Device Owner to this app. */
    fun deviceOwnerCommand(c: Context): String =
        "dpm set-device-owner ${c.packageName}/.TimeLockAdmin"

    /**
     * The full "approve by Shizuku" chain:
     *   1. `pm list users`
     *   2. `pm remove-user <id>` for every user whose id is NOT 0
     *   3. `dpm set-device-owner <pkg>/.TimeLockAdmin`
     *
     * BLOCKING and sequential; call off the main thread (prefer [provisionAsync]).
     * Returns a populated report either way.
     */
    fun provisionDeviceOwner(c: Context): ProvisionReport {
        val rep = ProvisionReport()
        rep.adbCommand = deviceOwnerCommand(c)

        if (!isServiceAlive()) {
            rep.failure = formatExecutionError(
                "Shizuku service is not running - open Shizuku and start it first")
            return rep
        }
        if (!isPermissionGranted()) {
            rep.failure = formatExecutionError(
                "Shizuku permission is not granted for this app yet")
            return rep
        }

        // 1. enumerate users
        val users = run("pm list users")
        rep.usersOutput = if (users.ran()) users.output else "(failed) ${users.error}"
        val ids = parseUserIds(users.output)

        // 2. remove every secondary user (anything other than 0)
        val remLog = StringBuilder()
        for (id in ids) {
            if (id == 0) continue
            val rem = run("pm remove-user $id")
            rep.removedUsers.add(id)
            remLog.append("pm remove-user ").append(id).append("  ->  exit ").append(rem.exitCode)
            if (rem.output.isNotEmpty()) remLog.append('\n').append(rem.output)
            remLog.append('\n')
        }
        if (remLog.isEmpty()) remLog.append("No secondary users found \u2014 nothing to remove.\n")
        rep.removeOutput = remLog.toString().trim()

        // 3. grant Device Owner (the same command shown on screen, run live)
        val owner = run(rep.adbCommand)
        rep.ownerOutput = if (owner.ran()) {
            if (owner.output.isNotEmpty()) owner.output else "(no output)"
        } else {
            "(failed) ${owner.error}"
        }

        rep.success = owner.ran() && owner.ok
        if (!rep.success) {
            if (owner.ran()) {
                val reason = if (owner.output.isNotEmpty()) owner.output else "no output"
                rep.failure = formatExecutionError(
                    "dpm set-device-owner ${c.packageName}/.TimeLockAdmin failed - " +
                        "$reason - remove every account/user and retry")
            } else {
                rep.failure = formatExecutionError(owner.error)
            }
        }
        return rep
    }

    /**
     * Run [provisionDeviceOwner] asynchronously on the single worker thread and
     * deliver the report on the MAIN thread.
     *
     * @return false when a run is already in progress (the request was ignored);
     *         true when it was accepted and will be delivered.
     */
    fun provisionAsync(c: Context, onDone: (ProvisionReport) -> Unit): Boolean {
        // Atomic gate: reject a second concurrent chain instead of queuing it.
        if (!busy.compareAndSet(false, true)) return false

        val app = c.applicationContext
        try {
            worker.execute {
                val rep: ProvisionReport = try {
                    provisionDeviceOwner(app)
                } catch (t: Throwable) {
                    Log.w(TAG, "provisionDeviceOwner", t)
                    ProvisionReport().apply {
                        adbCommand = deviceOwnerCommand(app)
                        failure = t.javaClass.simpleName + ": " + t.message
                    }
                }
                busy.set(false)
                main.post {
                    try {
                        onDone(rep)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onDone", t)
                    }
                }
            }
        } catch (t: Throwable) {
            // Executor rejected the task (e.g. shutting down): release the gate.
            Log.w(TAG, "provisionAsync submit", t)
            busy.set(false)
            return false
        }
        return true
    }
}
