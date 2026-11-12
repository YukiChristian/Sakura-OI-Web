package com.sakura.webapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Where the user types the server addresses, following the "Conexión inicial" design. On first
 * launch it is step one of the setup assistant (then come the permissions); later it is reached
 * from the phone settings screen to change them. "Guardar" stays disabled until a test shows
 * that at least one address answers.
 */
class ConnectionActivity : ComponentActivity() {
    private lateinit var ui: SetupUi
    private lateinit var local: SetupUi.AddressField
    private lateinit var exterior: SetupUi.AddressField
    private lateinit var localPill: SetupUi.StatusPill
    private lateinit var exteriorPill: SetupUi.StatusPill
    private lateinit var saveButton: View
    private lateinit var testButton: View
    private val setupMode by lazy { intent.getBooleanExtra(EXTRA_SETUP, false) }
    /** Addresses that passed the last test, so editing a field disables "Guardar" again. */
    private var testedLocal: String? = null
    private var testedExterior: String? = null
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerAddress.load(this)
        ui = SetupUi(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui.styleWindow()

        val column = ui.column()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ui.ground)
            addView(column)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)

        column.addView(
            if (setupMode) ui.header(getString(R.string.setup_welcome), getString(R.string.setup_intro))
            else ui.header(getString(R.string.connection_edit_title), getString(R.string.connection_edit_intro))
        )

        val homeCard = ui.card()
        homeCard.addView(ui.cardHeader(R.drawable.ic_ui_home, getString(R.string.setup_local_label), getString(R.string.setup_local_help)))
        local = ui.AddressField(getString(R.string.setup_local_hint), display(ServerAddress.local))
        homeCard.addView(local.view)
        localPill = ui.StatusPill()
        homeCard.addView(localPill.view)
        column.addView(homeCard)

        val awayCard = ui.card()
        awayCard.addView(ui.cardHeader(
            R.drawable.ic_ui_globe, getString(R.string.setup_exterior_label),
            getString(R.string.setup_exterior_help), chip = getString(R.string.setup_optional)
        ))
        exterior = ui.AddressField(getString(R.string.setup_exterior_hint), display(ServerAddress.exterior))
        exterior.view.imeOptions = EditorInfo.IME_ACTION_DONE
        awayCard.addView(exterior.view)
        exteriorPill = ui.StatusPill()
        awayCard.addView(exteriorPill.view)
        column.addView(awayCard)

        // Push the buttons to the bottom on tall screens.
        column.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { topMargin = ui.dp(16) })

        saveButton = ui.primaryButton(
            getString(if (setupMode) R.string.setup_save_start else R.string.setup_save)
        ) { save() }
        testButton = ui.secondaryButton(getString(R.string.setup_test), R.drawable.ic_ui_refresh) { runTest() }
        column.addView(saveButton)
        column.addView(testButton)
        column.addView(ui.footer(getString(R.string.setup_footer)))

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshState()
        }
        local.view.addTextChangedListener(watcher)
        exterior.view.addTextChangedListener(watcher)
        refreshState()
    }

    /** Shows saved addresses the way the user typed them, without https:// and the slash. */
    private fun display(address: String?): String? = address?.removePrefix("https://")?.trimEnd('/')

    /** "Guardar" only after a passing test of exactly what is typed now. */
    private fun refreshState() {
        val l = ServerAddress.normalize(local.view.text.toString())
        val e = ServerAddress.normalize(exterior.view.text.toString())
        val tested = (l != null || e != null) && l == testedLocal && e == testedExterior
        if (!tested && !testing) {
            if (l != testedLocal) { localPill.hide(); local.hasError = false }
            if (e != testedExterior) { exteriorPill.hide(); exterior.hasError = false }
        }
        ui.setEnabledLook(saveButton, tested && !testing)
        ui.setEnabledLook(testButton, !testing && (local.view.text.isNotBlank() || exterior.view.text.isNotBlank()))
    }

    private fun runTest() {
        val localText = local.view.text.toString()
        val exteriorText = exterior.view.text.toString()
        val l = ServerAddress.normalize(localText)
        val e = ServerAddress.normalize(exteriorText)
        testedLocal = null
        testedExterior = null
        val invalid = getString(R.string.check_invalid)
        if (localText.isNotBlank() && l == null) { localPill.show(SetupUi.Pill.ERROR, invalid); local.hasError = true }
        if (exteriorText.isNotBlank() && e == null) { exteriorPill.show(SetupUi.Pill.ERROR, invalid); exterior.hasError = true }
        if ((localText.isNotBlank() && l == null) || (exteriorText.isNotBlank() && e == null)) return refreshState()
        if (l == null && e == null) return

        testing = true
        val testingText = getString(R.string.check_testing)
        if (l != null) { localPill.show(SetupUi.Pill.TESTING, testingText); local.hasError = false } else localPill.hide()
        if (e != null) { exteriorPill.show(SetupUi.Pill.TESTING, testingText); exterior.hasError = false } else exteriorPill.hide()
        refreshState()
        Thread {
            val localError = l?.let { ServerAddress.check(this, it, TEST_TIMEOUT_MS) }
            val exteriorError = e?.let { ServerAddress.check(this, it, TEST_TIMEOUT_MS) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                testing = false
                val connected = getString(R.string.check_connected)
                if (l != null) {
                    if (localError == null) localPill.show(SetupUi.Pill.OK, connected)
                    else localPill.show(SetupUi.Pill.ERROR, localError)
                    local.hasError = localError != null
                }
                if (e != null) {
                    if (exteriorError == null) exteriorPill.show(SetupUi.Pill.OK, connected)
                    else exteriorPill.show(SetupUi.Pill.ERROR, exteriorError)
                    exterior.hasError = exteriorError != null
                }
                val anyOk = (l != null && localError == null) || (e != null && exteriorError == null)
                if (anyOk) {
                    testedLocal = l
                    testedExterior = e
                }
                refreshState()
            }
        }.start()
    }

    private fun save() {
        ServerAddress.save(this, testedLocal, testedExterior)
        if (setupMode) startActivity(PermissionsActivity.intent(this, setup = true))
        finish()
    }

    companion object {
        private const val EXTRA_SETUP = "setup"
        private const val TEST_TIMEOUT_MS = 6_000

        fun intent(context: Context, setup: Boolean): Intent =
            Intent(context, ConnectionActivity::class.java).putExtra(EXTRA_SETUP, setup)
    }
}
