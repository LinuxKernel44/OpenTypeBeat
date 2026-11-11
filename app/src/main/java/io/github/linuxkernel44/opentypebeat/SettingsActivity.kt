package io.github.linuxkernel44.opentypebeat

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.preference.PreferenceFragmentCompat
import io.github.linuxkernel44.opentypebeat.gecko.GeckoHolder

@UnstableApi
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<androidx.preference.Preference>(Prefs.KEY_REINSTALL_EXTENSIONS)
                ?.setOnPreferenceClickListener {
                    val ctx = requireContext()
                    GeckoHolder.installMissingAddons(GeckoHolder.runtime(ctx), force = true)
                    Toast.makeText(ctx, R.string.reinstalling_extensions, Toast.LENGTH_SHORT).show()
                    true
                }
        }
    }
}
