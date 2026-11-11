package io.github.linuxkernel44.opentypebeat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.material.chip.Chip
import io.github.linuxkernel44.opentypebeat.databinding.ActivityMainBinding
import io.github.linuxkernel44.opentypebeat.gecko.GeckoHolder
import io.github.linuxkernel44.opentypebeat.player.PlayerHolder
import org.mozilla.geckoview.GeckoSession
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var session: GeckoSession
    private var canGoBack = false
    private var currentUrl = ""
    private var seeking = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            binding.root.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateMiniPlayer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        prefs = Prefs(this)

        requestNotificationPermission()
        buildGenreChips()
        setupMiniPlayer()

        session = GeckoHolder.session(this, prefs.desktopSite)
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                currentUrl = url.orEmpty()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@MainActivity.canGoBack = canGoBack
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                binding.progress.isVisible = true
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                binding.progress.progress = progress
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                binding.progress.isVisible = false
            }
        }
        binding.geckoView.setSession(session)

        GeckoHolder.onStatus = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
        (application as OpenTypeBeatApp).coordinator.onMessage = { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBack) session.goBack() else moveTaskToBack(true)
            }
        })

        val launchUri = intent?.data
        if (launchUri != null) {
            session.loadUri(launchUri.toString())
        } else if (savedInstanceState == null && currentUrl.isEmpty()) {
            session.loadUri(homeUrl())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { session.loadUri(it.toString()) }
    }

    override fun onStart() {
        super.onStart()
        PlayerHolder.get(this).addListener(playerListener)
        updateMiniPlayer()
        binding.root.post(progressTicker)
    }

    override fun onStop() {
        binding.root.removeCallbacks(progressTicker)
        PlayerHolder.get(this).removeListener(playerListener)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            binding.geckoView.releaseSession()
        }
        super.onDestroy()
    }

    // ---- Menu ---------------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menu.findItem(R.id.action_desktop).isChecked = prefs.desktopSite
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_music -> session.loadUri(MUSIC_URL)
            R.id.action_youtube -> session.loadUri(YOUTUBE_URL)
            R.id.action_reload -> session.reload()
            R.id.action_signin -> session.loadUri(SIGN_IN_URL)
            R.id.action_desktop -> {
                prefs.desktopSite = !item.isChecked
                item.isChecked = prefs.desktopSite
                applyDesktopMode(prefs.desktopSite)
            }
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun applyDesktopMode(desktop: Boolean) {
        session.settings.userAgentMode =
            if (desktop) org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode =
            if (desktop) org.mozilla.geckoview.GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else org.mozilla.geckoview.GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        session.reload()
    }

    // ---- Beat genre chips ---------------------------------------------------------------------

    private fun buildGenreChips() {
        for (genre in BeatGenres.all) {
            val chip = Chip(this).apply {
                text = genre
                isCheckable = false
                setOnClickListener {
                    session.loadUri(BeatGenres.searchUrl(genre, music = !currentUrl.contains("www.youtube.com")))
                }
            }
            binding.chipGroup.addView(chip)
        }
    }

    // ---- Mini player --------------------------------------------------------------------------

    private fun setupMiniPlayer() {
        val player = PlayerHolder.get(this)
        binding.btnPlayPause.setOnClickListener {
            if (player.playWhenReady) player.pause() else player.play()
        }
        binding.btnNext.setOnClickListener {
            (application as OpenTypeBeatApp).coordinator.sendCommand("next")
        }
        binding.btnPrevious.setOnClickListener {
            (application as OpenTypeBeatApp).coordinator.sendCommand("previous")
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.timeCurrent.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                player.seekTo(bar.progress.toLong())
            }
        })
    }

    private fun updateMiniPlayer() {
        val player = PlayerHolder.get(this)
        val hasItem = player.currentMediaItem != null
        binding.miniPlayer.isVisible = hasItem
        if (!hasItem) return
        val md: MediaMetadata = player.mediaMetadata
        binding.trackTitle.text = md.title ?: ""
        binding.trackArtist.text = md.artist ?: ""
        binding.btnPlayPause.setImageResource(
            if (player.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play,
        )
        updateProgress()
    }

    private fun updateProgress() {
        if (seeking) return
        val player = PlayerHolder.get(this)
        val dur = player.duration.takeIf { it > 0 } ?: 0L
        binding.seekBar.max = dur.toInt()
        binding.seekBar.progress = player.currentPosition.toInt()
        binding.seekBar.secondaryProgress = player.bufferedPosition.toInt()
        binding.timeCurrent.text = formatTime(player.currentPosition)
        binding.timeTotal.text = formatTime(dur)
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    // ---- Misc ---------------------------------------------------------------------------------

    private fun homeUrl() = if (prefs.startPage == "youtube") YOUTUBE_URL else MUSIC_URL

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val MUSIC_URL = "https://music.youtube.com/"
        const val YOUTUBE_URL = "https://www.youtube.com/"
        const val SIGN_IN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F"
    }
}
