package com.mg4.streams

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.InputStream
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var fullPlayerView: PlayerView
    private lateinit var mediaFrame: FrameLayout
    private lateinit var logoView: ImageView
    private lateinit var stationGroup: TextView
    private lateinit var stationName: TextView
    private lateinit var nowText: TextView
    private lateinit var bannerText: TextView
    private lateinit var searchInput: EditText
    private lateinit var settingsButton: ImageButton
    private lateinit var playButton: ImageButton
    private lateinit var starButton: ImageButton
    private lateinit var stationGrid: GridView
    private lateinit var emptyText: TextView
    private lateinit var gridTitle: TextView
    private lateinit var tabViews: Map<Tab, TextView>
    private lateinit var favsToggle: TextView
    private lateinit var allToggle: TextView

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val gearGate = SaicGearGate()
    /** Playlist downloads and file I/O (including favourites). */
    private val worker = Executors.newSingleThreadExecutor()
    /** Gear reads only, so a slow download can never delay the gate. */
    private val gearThread = Executors.newSingleThreadExecutor()
    /** Guide (EPG) download and parsing, which can take a few seconds. */
    private val guideThread = Executors.newSingleThreadExecutor()
    /** Logo downloads and decoding. */
    private val logoThreads = Executors.newFixedThreadPool(3)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logoLoader: LogoLoader
    private lateinit var prefs: android.content.SharedPreferences

    /** Video is allowed only when the latest gear read was P. Starts false (fail closed). */
    private var videoAllowed = false
    /** True while a gear read is running. A read that never returns keeps video off. */
    private var gearReadInFlight = false
    private var currentChannelName: String? = null
    /** The station on screen (playing, or restored from last time). Null if unknown. */
    private var currentChannel: Channel? = null
    private var nowPlaying: String? = null
    /** True after Stop, until Play or a new station. */
    private var stopped = false
    private var channels: List<Channel> = emptyList()
    private var channelsByUrl: Map<String, Channel> = emptyMap()
    private var listsLoaded = false
    private var restoreDone = false
    private var epgUrls: List<String> = emptyList()
    private var guide: EpgGuide = EpgGuide.EMPTY
    private var epgRefreshing = false
    /** A forced guide refresh asked for while another was running. */
    private var epgForcePending = false
    /** Retry or error text from PlaybackService; shown instead of the station line while set. */
    private var serviceStatus: String? = null
    /** A short message (update failed, import done...) shown in the banner until the next play. */
    private var message: String? = null
    /** tvg-id of the playing channel, for the guide. */
    private var currentTvgId: String? = null
    private lateinit var epgStore: EpgStore

    private var tab = Tab.RADIO
    private var query = ""
    private val favourites = LinkedHashSet<String>()
    private var favouritesOnly = false
    private var favouritesChosen = false
    private var shown: List<Channel> = emptyList()
    private var playingUrl: String? = null
    private lateinit var favStore: FavouritesStore
    private val adapter = StationAdapter()

    private var fullscreen = false
    private var settingsDialog: AlertDialog? = null
    private var shownLogo: String? = null
    private var shownFrameVideo: Boolean? = null
    private val timeFormat: DateFormat by lazy { android.text.format.DateFormat.getTimeFormat(this) }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitFullscreen()
    }

    /** Updates the guide text once a minute. */
    private val guideTick = object : Runnable {
        override fun run() {
            if (!guide.isEmpty) updateStatus()
            handler.postDelayed(this, 60_000L)
        }
    }

    /** One playlist file per tab: radio.m3u, tv.m3u, custom.m3u. Never merged on disk. */
    private lateinit var slotFiles: Map<Tab, File>
    /** The slot an Import from the file picker goes into. */
    private var importSlot: Tab = Tab.RADIO

    /** Bumped in onStart and onStop, so a gear read from an earlier visible period is ignored. */
    private var gateGeneration = 0

    private val pollGate = object : Runnable {
        override fun run() {
            gearGate.connect(this@MainActivity)
            if (gearReadInFlight) {
                // The previous read has not returned, so the gear is unknown: fail closed.
                setVideoAllowed(false)
            } else {
                gearReadInFlight = true
                val generation = gateGeneration
                gearThread.execute {
                    val parked = gearGate.isParked()
                    runOnUiThread {
                        gearReadInFlight = false
                        // Drop a read that started before onStop: it may be stale.
                        if (!isDestroyed && generation == gateGeneration) setVideoAllowed(parked)
                    }
                }
            }
            handler.postDelayed(this, GEAR_POLL_MS)
        }
    }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFromUri(it, importSlot) }
    }

    private val openUrlList = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importUrlList(it, importSlot) }
    }

    private val updater by lazy { Updater(this) }
    private var updateRunning = false
    /** Update check, download and install, so they never hold up playlist work on [worker]. */
    private val updateThread = Executors.newSingleThreadExecutor()

    /** Gives up waiting for an install result (e.g. the install screen was backed out of). */
    private val installTimeout = Runnable {
        if (updateRunning) {
            updater.cancelPending()
            updateRunning = false
            message = "Update didn't finish. Try again from Settings."
            updateStatus()
        }
    }

    private val controllerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            // Stream titles (ICY) replace the station name in the player's metadata.
            nowPlaying = mediaMetadata.title?.toString()
                ?.takeIf { it.isNotBlank() && it != currentChannelName }
            updateStatus()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Clear the old station's title, then show the new station's name from its metadata.
            // Also fires for previous/next from the steering wheel.
            nowPlaying = null
            message = null
            adoptItem(mediaItem)
            urlOf(mediaItem)?.let { prefs.edit().putString(PREF_LAST_URL, it).apply() }
            updateStatus()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Playback restarted from elsewhere (notification, steering wheel, player controls).
            if (isPlaying && stopped) stopped = false
            updateStatus()
        }

        override fun onPlaybackStateChanged(playbackState: Int) = updateStatus()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && stopped) stopped = false
            updateStatus()
        }

        override fun onTracksChanged(tracks: Tracks) = updateVideoUi()

        override fun onPlayerError(error: PlaybackException) {
            // PlaybackService retries and sends a readable status; this is only a fallback.
            if (serviceStatus == null) {
                message = "Playback error: ${error.errorCodeName}"
                updateStatus()
            }
        }
    }

    private val sessionListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            serviceStatus = extras.getString(PlaybackService.EXTRA_STATUS)
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback(this, backCallback)

        prefs = getSharedPreferences("mg4_streams", Context.MODE_PRIVATE)
        slotFiles = mapOf(
            Tab.RADIO to File(filesDir, "radio.m3u"),
            Tab.TV to File(filesDir, "tv.m3u"),
            Tab.MY_LIST to File(filesDir, "custom.m3u"),
        )
        epgStore = EpgStore(File(filesDir, "epg.tsv"))
        favStore = FavouritesStore(File(filesDir, "favourites.txt"))
        logoLoader = LogoLoader(File(filesDir, "logos"), logoThreads, handler)

        playerView = findViewById(R.id.playerView)
        fullPlayerView = findViewById(R.id.fullPlayerView)
        mediaFrame = findViewById(R.id.mediaFrame)
        logoView = findViewById(R.id.logoView)
        stationGroup = findViewById(R.id.stationGroup)
        stationName = findViewById(R.id.stationName)
        nowText = findViewById(R.id.nowText)
        bannerText = findViewById(R.id.bannerText)
        searchInput = findViewById(R.id.searchInput)
        settingsButton = findViewById(R.id.settingsButton)
        playButton = findViewById(R.id.playButton)
        starButton = findViewById(R.id.starButton)
        stationGrid = findViewById(R.id.stationGrid)
        emptyText = findViewById(R.id.emptyText)
        gridTitle = findViewById(R.id.gridTitle)
        favsToggle = findViewById(R.id.favsToggle)
        allToggle = findViewById(R.id.allToggle)
        tabViews = mapOf(
            Tab.RADIO to findViewById(R.id.tabRadio),
            Tab.TV to findViewById(R.id.tabTv),
            Tab.MY_LIST to findViewById(R.id.tabMy),
        )

        tab = Tab.fromName(prefs.getString(PREF_TAB, null))
        favouritesChosen = prefs.contains(PREF_FAVS_ONLY)
        favouritesOnly = prefs.getBoolean(PREF_FAVS_ONLY, false)

        tabViews.forEach { (t, view) ->
            view.setOnClickListener {
                tab = t
                prefs.edit().putString(PREF_TAB, t.name).apply()
                refreshShown()
            }
        }
        favsToggle.setOnClickListener { chooseFavouritesOnly(true) }
        allToggle.setOnClickListener { chooseFavouritesOnly(false) }

        stationGrid.adapter = adapter
        stationGrid.setOnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { playChannel(it) }
        }
        stationGrid.setOnItemLongClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { toggleFavourite(it) }
            true
        }

        searchInput.addTextChangedListener { text ->
            query = text?.toString().orEmpty()
            refreshShown()
        }
        searchInput.setOnTouchListener { _, event ->
            // Keyboard only when parked. While driving, a tap just explains why.
            if (!videoAllowed) {
                if (event.action == MotionEvent.ACTION_UP) showParkedOnly()
                true
            } else {
                false
            }
        }
        searchInput.setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        settingsButton.setOnClickListener { showSettings() }

        playButton.setOnClickListener { if (isPlayingUi()) stopPlayback() else resume() }
        findViewById<ImageButton>(R.id.prevButton).setOnClickListener { stepStation(-1) }
        findViewById<ImageButton>(R.id.nextButton).setOnClickListener { stepStation(1) }
        starButton.setOnClickListener { currentChannel?.let { toggleFavourite(it) } }
        // PlayerView ignores clicks when its controller is off, so use a touch listener.
        playerView.setOnTouchListener(tapListener { enterFullscreen() })
        fullPlayerView.setOnTouchListener(tapListener { exitFullscreen() })

        updateParkedUi()
        gearGate.connect(this)
        loadFavourites()
        loadPlaylists()
        updateStatus()
    }

    override fun onStart() {
        super.onStart()
        gateGeneration++
        setVideoAllowed(false)
        connectToService()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollGate)
        handler.post(pollGate)
        handler.removeCallbacks(guideTick)
        handler.post(guideTick)
    }

    override fun onStop() {
        // Playback continues in PlaybackService. Video is switched off while the screen is away,
        // so it can't show when the car is no longer checked.
        super.onStop()
        gateGeneration++
        handler.removeCallbacks(pollGate)
        handler.removeCallbacks(guideTick)
        setVideoAllowed(false)
        exitFullscreen()
        playerView.player = null
        fullPlayerView.player = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollGate)
        handler.removeCallbacks(guideTick)
        settingsDialog?.dismiss()
        controller?.removeListener(controllerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        gearGate.disconnect(this)
        // shutdown (not shutdownNow) so a queued favourites save still runs.
        worker.shutdown()
        gearThread.shutdownNow()
        guideThread.shutdownNow()
        updateThread.shutdownNow()
        handler.removeCallbacks(installTimeout)
        logoThreads.shutdownNow()
        super.onDestroy()
    }

    /** Connects to PlaybackService, reconnecting if the controller has dropped. */
    private fun connectToService() {
        val current = controller
        if (current != null && current.isConnected) {
            updateVideoUi()
            return
        }
        controller?.removeListener(controllerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).setListener(sessionListener).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isDestroyed) return@addListener
            try {
                val built = future.get()
                controller = built
                built.addListener(controllerListener)
                adoptItem(built.currentMediaItem)
                serviceStatus = built.sessionExtras.getString(PlaybackService.EXTRA_STATUS)
                applyTrackGate()
                tryRestore()
                updateStatus()
            } catch (e: ExecutionException) {
                message = "Playback service unavailable: ${e.cause?.message}"
                updateStatus()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Takes the station identity from a media item (playlist entry) the service is playing. */
    private fun adoptItem(item: MediaItem?) {
        if (item == null) return
        val url = urlOf(item)
        playingUrl = url
        currentChannel = url?.let { channelsByUrl[it] } ?: currentChannel?.takeIf { it.url == url }
        currentChannelName = item.mediaMetadata.title?.toString() ?: currentChannelName
        currentTvgId = item.mediaMetadata.extras?.getString(StreamMediaSourceFactory.EXTRA_TVG_ID)
        adapter.notifyDataSetChanged()
    }

    private fun urlOf(item: MediaItem?): String? =
        item?.mediaId?.takeIf { it.startsWith("http", true) }
            ?: item?.localConfiguration?.uri?.toString()

    /**
     * Once both the controller and the lists are ready: if the service already has a station,
     * the screen follows it. Otherwise show the last station, and start it only if the
     * auto-resume setting is on.
     */
    private fun tryRestore() {
        val player = controller ?: return
        if (restoreDone || !listsLoaded) return
        restoreDone = true
        if (player.currentMediaItem != null) {
            adoptItem(player.currentMediaItem)
            return
        }
        val channel = prefs.getString(PREF_LAST_URL, null)?.let { channelsByUrl[it] } ?: return
        currentChannel = channel
        currentChannelName = channel.name
        currentTvgId = channel.tvgId
        if (prefs.getBoolean(PREF_AUTO_RESUME, false)) playChannel(channel) else updateStatus()
    }

    // ---- Lists, tabs, favourites -------------------------------------------------------------

    private fun loadFavourites() {
        worker.execute {
            val loaded = favStore.load()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                favourites.clear()
                favourites.addAll(loaded)
                // Default to Favourites once there are any, until the user chooses.
                if (!favouritesChosen) favouritesOnly = favourites.isNotEmpty()
                refreshShown()
                updateStatus()
            }
        }
    }

    private fun chooseFavouritesOnly(only: Boolean) {
        favouritesOnly = only
        favouritesChosen = true
        prefs.edit().putBoolean(PREF_FAVS_ONLY, only).apply()
        refreshShown()
    }

    private fun toggleFavourite(channel: Channel) {
        val now = FavouritesStore.toggle(favourites, channel.url)
        val snapshot = favourites.toList()
        worker.execute { runCatching { favStore.save(snapshot) } }
        message = null
        Toast.makeText(this, if (now) "Added ${channel.name} to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show()
        refreshShown()
        updateStatus()
    }

    /** Recomputes the grid from the tab, search text and favourites filter. */
    private fun refreshShown() {
        shown = StationLists.filter(channels, tab, query, favourites, favouritesOnly)
        adapter.notifyDataSetChanged()
        tabViews.forEach { (t, view) -> view.isSelected = t == tab }
        favsToggle.isSelected = favouritesOnly
        allToggle.isSelected = !favouritesOnly
        gridTitle.text = "${tab.label.uppercase()} · ${if (favouritesOnly) "FAVOURITES" else "ALL"}"
        val empty = when {
            shown.isNotEmpty() -> null
            !listsLoaded -> "Loading stations..."
            query.isNotBlank() -> "No stations match \"${query.trim()}\""
            favouritesOnly -> "No favourites here yet.\nLong-press a station to add one."
            else -> "No ${tab.label} playlist yet.\nAdd one in Settings (when parked)."
        }
        emptyText.text = empty.orEmpty()
        emptyText.visibility = if (empty == null) View.GONE else View.VISIBLE
    }

    /** Shows the saved lists, then re-downloads any slot whose saved URL is more than a day old. */
    private fun loadPlaylists() {
        showSavedLists()
        val stale = Tab.values().filter { slot ->
            savedUrls(slot).isNotEmpty() &&
                System.currentTimeMillis() - slotFiles.getValue(slot).lastModified() > PLAYLIST_MAX_AGE_MS
        }
        if (stale.isNotEmpty()) refreshSlots(stale, quiet = true)
        checkForUpdateDaily()
    }

    private fun showSavedLists() {
        worker.execute {
            val texts = readSavedLists()
            runOnUiThread { if (!isDestroyed) setPlaylistText(texts) }
        }
    }

    /** Each slot's text, kept apart. Runs on the worker thread. */
    private fun readSavedLists(): Map<Tab, String> =
        slotFiles.mapValues { (_, file) -> readOrEmpty(file) }

    private fun readOrEmpty(file: File): String =
        if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""

    /** The playlist addresses saved for [slot] (one per line in preferences). */
    private fun savedUrls(slot: Tab): List<String> =
        prefs.getString(PREF_URL_PREFIX + slot.name, null).orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun saveUrls(slot: Tab, urls: List<String>) {
        val editor = prefs.edit()
        if (urls.isEmpty()) editor.remove(PREF_URL_PREFIX + slot.name)
        else editor.putString(PREF_URL_PREFIX + slot.name, urls.joinToString("\n"))
        editor.apply()
    }

    /** Downloads every address and joins them into one playlist. Throws if any fails. */
    private fun fetchAll(urls: List<String>): String {
        val text = urls.joinToString("\n") { fetchText(it) }
        if (text.length > MAX_PLAYLIST_BYTES) throw IllegalStateException("playlists are larger than 8 MB together")
        return text
    }

    /**
     * Re-downloads each slot in [slots] from its saved URL. A slot that fails keeps its saved
     * list. [quiet] skips the progress message (used for the automatic refresh on start).
     */
    private fun refreshSlots(slots: List<Tab>, quiet: Boolean = false) {
        val jobs = slots.map { slot -> slot to savedUrls(slot) }.filter { it.second.isNotEmpty() }
        if (jobs.isEmpty()) {
            message = "No saved playlist addresses. Add one with Add URL."
            updateStatus()
            return
        }
        if (!quiet) {
            message = "Updating playlists..."
            updateStatus()
        }
        worker.execute {
            val failed = mutableListOf<String>()
            for ((slot, urls) in jobs) {
                try {
                    // All of a slot's addresses, or none: a failure keeps the saved list.
                    saveAtomically(slotFiles.getValue(slot), fetchAll(urls))
                } catch (e: Exception) {
                    failed += "${slot.label}: ${e.message}"
                }
            }
            val texts = readSavedLists()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setPlaylistText(texts, forceGuide = failed.size < jobs.size)
                if (failed.isNotEmpty()) {
                    message = "Update failed (${failed.joinToString("; ")})"
                    updateStatus()
                }
            }
        }
    }

    private fun fetchText(address: String): String {
        val connection = URL(address).openConnection()
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        return connection.getInputStream().use { readCapped(it) }
    }

    /** Parses each slot separately, so each channel knows which tab it belongs to. */
    private fun setPlaylistText(texts: Map<Tab, String>, forceGuide: Boolean = false) {
        val parsed = texts.mapValues { (_, text) -> M3uParser.parsePlaylist(text) }
        channels = Tab.values().flatMap { slot ->
            parsed[slot]?.channels.orEmpty().map { it.copy(source = slot) }
        }
        channelsByUrl = channels.associateBy { it.url }
        epgUrls = parsed.values.flatMap { it.epgUrls }.distinct()
        listsLoaded = true
        message = if (channels.isEmpty()) "No playlists yet. Open Settings (when parked) to add one." else null
        refreshShown()
        tryRestore()
        updateStatus()
        refreshGuide(force = forceGuide)
    }

    /**
     * Loads the cached guide, then downloads a new one if it is stale (or [force]). Only the
     * channels in the lists are kept. Runs on the worker thread; a failed download keeps the cache.
     */
    private fun refreshGuide(force: Boolean) {
        val urls = epgUrls
        val ids = channels.mapNotNull { it.tvgId }.toSet()
        if (urls.isEmpty() || ids.isEmpty()) return
        if (epgRefreshing) {
            if (force) epgForcePending = true
            return
        }
        epgRefreshing = true
        val cached = guide
        guideThread.execute {
            var current = if (cached.isEmpty) epgStore.loadCache() else cached
            runOnUiThread { if (!isDestroyed) applyGuide(current) }
            if (force || epgStore.needsRefresh(current, urls, ids)) {
                runCatching { epgStore.refresh(urls, ids) }
                    .onSuccess { current = it }
                    .onFailure { android.util.Log.w("MG4Streams", "guide download failed", it) }
            }
            val result = current
            runOnUiThread {
                epgRefreshing = false
                if (isDestroyed) return@runOnUiThread
                applyGuide(result)
                if (epgForcePending) {
                    epgForcePending = false
                    refreshGuide(force = true)
                }
            }
        }
    }

    private fun applyGuide(newGuide: EpgGuide) {
        guide = newGuide
        updateStatus()
    }

    /** Import replaces [slot]'s list with the file and forgets its saved URL. */
    private fun importFromUri(uri: Uri, slot: Tab) {
        worker.execute {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open file")
                val text = stream.use { readCapped(it) }
                saveAtomically(slotFiles.getValue(slot), text)
                saveUrls(slot, emptyList())
                val texts = readSavedLists()
                runOnUiThread { if (!isDestroyed) setPlaylistText(texts, forceGuide = true) }
            } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed) { message = "Import failed: ${e.message}"; updateStatus() } }
            }
        }
    }

    /**
     * Reads a text file of playlist addresses (see [UrlListParser]), saves them for each tab the
     * file names, and downloads them. Tabs the file doesn't mention are left alone. Bare addresses
     * go to [defaultSlot]. A tab whose download fails keeps its old list but saves the addresses.
     */
    private fun importUrlList(uri: Uri, defaultSlot: Tab) {
        message = "Reading address list..."
        updateStatus()
        worker.execute {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open file")
                val lists = UrlListParser.parse(stream.use { readCapped(it) }, defaultSlot)
                if (lists.isEmpty()) throw IllegalStateException("no http(s) addresses found")
                val failed = mutableListOf<String>()
                for ((slot, urls) in lists) {
                    saveUrls(slot, urls)
                    try {
                        saveAtomically(slotFiles.getValue(slot), fetchAll(urls))
                    } catch (e: Exception) {
                        failed += "${slot.label}: ${e.message}"
                    }
                }
                val summary = lists.entries.joinToString(", ") { (slot, urls) -> "${slot.label} ${urls.size}" }
                val texts = readSavedLists()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setPlaylistText(texts, forceGuide = true)
                    message = if (failed.isEmpty()) "Imported addresses ($summary)"
                    else "Imported addresses ($summary). Download failed: ${failed.joinToString("; ")}"
                    updateStatus()
                }
            } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed) { message = "Address list failed: ${e.message}"; updateStatus() } }
            }
        }
    }

    /** Add URL replaces [slot]'s list and saves the address for later updates. */
    private fun downloadPlaylist(address: String, slot: Tab) {
        if (!address.startsWith("http://", true) && !address.startsWith("https://", true)) {
            message = "Enter a full http:// or https:// playlist address"
            updateStatus()
            return
        }
        message = "Downloading ${slot.label} playlist..."
        updateStatus()
        worker.execute {
            try {
                saveAtomically(slotFiles.getValue(slot), fetchText(address))
                saveUrls(slot, listOf(address))
                val texts = readSavedLists()
                runOnUiThread { if (!isDestroyed) setPlaylistText(texts, forceGuide = true) }
            } catch (e: Exception) {
                runOnUiThread { if (!isDestroyed) { message = "Download failed: ${e.message}"; updateStatus() } }
            }
        }
    }

    /** Empties [slot]'s list and forgets its saved URL. */
    private fun clearSlot(slot: Tab) {
        worker.execute {
            slotFiles.getValue(slot).delete()
            saveUrls(slot, emptyList())
            val texts = readSavedLists()
            runOnUiThread { if (!isDestroyed) setPlaylistText(texts) }
        }
    }

    /** Reads at most MAX_PLAYLIST_BYTES so a huge response cannot exhaust memory. */
    private fun readCapped(input: InputStream): String {
        val buffer = ByteArray(MAX_PLAYLIST_BYTES + 1)
        var total = 0
        while (true) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
            if (total > MAX_PLAYLIST_BYTES) throw IllegalStateException("Playlist is larger than 8 MB")
        }
        return String(buffer, 0, total, Charsets.UTF_8)
    }

    /** Writes to a temp file, then renames, so a crash never leaves a half-written playlist. */
    private fun saveAtomically(target: File, text: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
    }

    // ---- Settings and the parked-only controls ------------------------------------------------

    private fun showParkedOnly() {
        Toast.makeText(this, "Available when parked", Toast.LENGTH_SHORT).show()
    }

    private fun showSettings() {
        if (!videoAllowed) {
            showParkedOnly()
            return
        }
        if (settingsDialog != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val urlInput = view.findViewById<EditText>(R.id.urlInput)
        val autoSwitch = view.findViewById<Switch>(R.id.autoResumeSwitch)
        autoSwitch.isChecked = prefs.getBoolean(PREF_AUTO_RESUME, false)
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_AUTO_RESUME, checked).apply()
        }
        // Which playlist slot the buttons act on. Starts on the tab being viewed.
        var slot = tab
        val slotButtons = mapOf(
            Tab.RADIO to view.findViewById<Button>(R.id.slotRadio),
            Tab.TV to view.findViewById<Button>(R.id.slotTv),
            Tab.MY_LIST to view.findViewById<Button>(R.id.slotMine),
        )
        fun selectSlot(newSlot: Tab) {
            slot = newSlot
            slotButtons.forEach { (s, b) -> b.isSelected = s == newSlot }
            val urls = savedUrls(newSlot)
            urlInput.setText(if (urls.size == 1) urls[0] else "")
            urlInput.hint = if (urls.size > 1) "${urls.size} saved addresses (from an address list)"
            else "Playlist URL (.m3u / .m3u8)"
        }
        slotButtons.forEach { (s, b) -> b.setOnClickListener { selectSlot(s) } }
        selectSlot(slot)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Close", null)
            .create()
        view.findViewById<Button>(R.id.addUrlButton).setOnClickListener {
            val address = urlInput.text.toString().trim()
            dialog.dismiss()
            downloadPlaylist(address, slot)
        }
        view.findViewById<Button>(R.id.importButton).setOnClickListener {
            importSlot = slot
            dialog.dismiss()
            openFile.launch(arrayOf("*/*"))
        }
        view.findViewById<Button>(R.id.clearButton).setOnClickListener {
            dialog.dismiss()
            clearSlot(slot)
        }
        view.findViewById<Button>(R.id.refreshButton).setOnClickListener {
            dialog.dismiss()
            refreshSlots(Tab.values().toList())
        }
        view.findViewById<Button>(R.id.importUrlsButton).setOnClickListener {
            importSlot = slot
            dialog.dismiss()
            openUrlList.launch(arrayOf("text/*", "application/octet-stream"))
        }
        view.findViewById<TextView>(R.id.versionText).text =
            "Version ${BuildConfig.VERSION_NAME} · updates from github.com/${BuildConfig.UPDATE_REPO}"
        view.findViewById<Button>(R.id.updateButton).setOnClickListener {
            dialog.dismiss()
            checkForUpdate(userAsked = true)
        }
        dialog.setOnDismissListener { settingsDialog = null }
        settingsDialog = dialog
        dialog.show()
    }

    // ---- App updates -------------------------------------------------------------------------

    /** Checks GitHub at most once a day, quietly; only says something if there is an update. */
    private fun checkForUpdateDaily() {
        val last = prefs.getLong(PREF_UPDATE_CHECKED, 0L)
        if (System.currentTimeMillis() - last < UPDATE_CHECK_INTERVAL_MS) return
        checkForUpdate(userAsked = false)
    }

    private fun checkForUpdate(userAsked: Boolean) {
        if (updateRunning) {
            if (userAsked) {
                message = "An update is already in progress"
                updateStatus()
            }
            return
        }
        updateRunning = true
        if (userAsked) {
            message = "Checking for updates..."
            updateStatus()
        }
        updateThread.execute {
            val result = runCatching { updater.latest() }
            runOnUiThread {
                updateRunning = false
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { release ->
                    prefs.edit().putLong(PREF_UPDATE_CHECKED, System.currentTimeMillis()).apply()
                    when {
                        release != null && Updater.isNewer(release.tag, BuildConfig.VERSION_NAME) ->
                            if (userAsked) offerUpdate(release)
                            else message = "Update ${release.tag} available. Open Settings to install."
                        userAsked -> message = "Up to date (version ${BuildConfig.VERSION_NAME})"
                    }
                }.onFailure {
                    if (userAsked) message = "Update check failed: ${it.message}"
                }
                updateStatus()
            }
        }
    }

    private fun offerUpdate(release: Updater.Release) {
        message = null
        val notes = release.notes.take(600).ifEmpty { "No release notes." }
        AlertDialog.Builder(this)
            .setTitle("Update to ${release.tag}?")
            .setMessage("You have ${BuildConfig.VERSION_NAME}.\n\n$notes")
            .setPositiveButton("Download and install") { _, _ -> installUpdate(release) }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Downloads, checks (package, version, signing key), then installs the release APK. */
    private fun installUpdate(release: Updater.Release) {
        if (updateRunning) return
        updateRunning = true
        message = "Downloading update..."
        updateStatus()
        updateThread.execute {
            try {
                val apk = updater.download(release) { percent ->
                    if (percent >= 0 && percent % 5 == 0) runOnUiThread {
                        if (!isDestroyed) { message = "Downloading update... $percent%"; updateStatus() }
                    }
                }
                updater.verify(apk)?.let { throw IllegalStateException(it) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    message = "Installing update. The app will restart."
                    updateStatus()
                    handler.removeCallbacks(installTimeout)
                    handler.postDelayed(installTimeout, INSTALL_TIMEOUT_MS)
                }
                // Copies the APK into the install session: background thread.
                updater.install(apk) { error ->
                    handler.removeCallbacks(installTimeout)
                    updateRunning = false
                    if (error != null && !isDestroyed) {
                        message = "Update failed: $error"
                        updateStatus()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    handler.removeCallbacks(installTimeout)
                    updateRunning = false
                    if (!isDestroyed) { message = "Update failed: ${e.message}"; updateStatus() }
                }
            }
        }
    }

    /** Search and settings are usable only while parked; dimmed otherwise. */
    private fun updateParkedUi() {
        val allowed = videoAllowed
        searchInput.isFocusable = allowed
        searchInput.isFocusableInTouchMode = allowed
        searchInput.alpha = if (allowed) 1f else 0.5f
        settingsButton.alpha = if (allowed) 1f else 0.5f
        if (!allowed) {
            searchInput.clearFocus()
            hideKeyboard()
            settingsDialog?.dismiss()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ---- Playback ----------------------------------------------------------------------------

    private fun mediaItemFor(channel: Channel): MediaItem {
        // Headers and the guide id travel to PlaybackService in the metadata extras.
        val extras = Bundle().apply {
            channel.userAgent?.let { putString(StreamMediaSourceFactory.EXTRA_USER_AGENT, it) }
            channel.referrer?.let { putString(StreamMediaSourceFactory.EXTRA_REFERRER, it) }
            channel.tvgId?.let { putString(StreamMediaSourceFactory.EXTRA_TVG_ID, it) }
        }
        return MediaItem.Builder()
            .setMediaId(channel.url)
            .setUri(channel.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(channel.name)
                    .setArtist(channel.group)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    /** The shown list is the playlist, so previous/next step through what is on screen. */
    private fun playChannel(channel: Channel, list: List<Channel>? = null) {
        val player = controller ?: return
        val source = list ?: shown.takeIf { l -> l.any { it.url == channel.url } } ?: listOf(channel)
        val index = source.indexOfFirst { it.url == channel.url }.coerceAtLeast(0)
        currentChannel = channel
        currentChannelName = channel.name
        currentTvgId = channel.tvgId
        playingUrl = channel.url
        nowPlaying = null
        serviceStatus = null
        message = null
        prefs.edit().putString(PREF_LAST_URL, channel.url).apply()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.setMediaItems(source.map { mediaItemFor(it) }, index, C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
        stopped = false
        adapter.notifyDataSetChanged()
        updateStatus()
    }

    /** True if the player's playlist is exactly [list], in order. */
    private fun playlistMatches(player: Player, list: List<Channel>): Boolean {
        if (player.mediaItemCount != list.size) return false
        return list.indices.all { urlOf(player.getMediaItemAt(it)) == list[it].url }
    }

    /** On-screen previous/next. The steering wheel does the same through the session. */
    private fun stepStation(delta: Int) {
        val player = controller ?: return
        val list = shown
        val target = StationLists.step(list, currentChannel?.url ?: playingUrl, delta)
        if (target < 0) return
        if (playlistMatches(player, list)) {
            player.seekTo(target, C.TIME_UNSET)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
            stopped = false
            updateStatus()
        } else {
            playChannel(list[target], list)
        }
    }

    /**
     * Restarts the current station. prepare() reconnects after a stop, so a live stream resumes
     * at the live point rather than from stale buffer.
     */
    private fun resume() {
        val player = controller ?: return
        if (player.currentMediaItem == null) {
            val channel = currentChannel ?: shown.firstOrNull()
            if (channel == null) {
                message = "Pick a station to play"
                updateStatus()
            } else {
                playChannel(channel)
            }
            return
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = true
        stopped = false
        updateStatus()
    }

    /** Stops playback and drops the stream connection. The station stays selected for Play. */
    private fun stopPlayback() {
        val player = controller ?: return
        // pause() first: stop() alone keeps playWhenReady and the last error, so a pending
        // reconnect in PlaybackService could restart the stream.
        player.pause()
        player.stop()
        stopped = true
        nowPlaying = null
        updateStatus()
    }

    /** What the Play/Stop button shows: Stop while playing or trying to (reconnecting). */
    private fun isPlayingUi(): Boolean {
        val player = controller ?: return false
        if (stopped || !player.playWhenReady) return false
        if (player.playbackState != Player.STATE_IDLE) return true
        val status = serviceStatus ?: return player.playerError == null && player.currentMediaItem != null
        return status.startsWith("Reconnecting") || status.startsWith("Waiting")
    }

    // ---- The video gate ----------------------------------------------------------------------

    private fun setVideoAllowed(allow: Boolean) {
        if (videoAllowed == allow) return
        videoAllowed = allow
        // Closing the gate: leave full screen first, then drop the video track.
        if (!allow) exitFullscreen()
        applyTrackGate()
        updateParkedUi()
        updateStatus()
    }

    /** Audio always plays. Video tracks are disabled unless the latest gear read was P. */
    private fun applyTrackGate() {
        val player = controller ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoAllowed)
            .build()
    }

    /** True if the station on screen is a TV station (or a custom entry that has video). */
    private fun isVideoStation(): Boolean {
        val channel = currentChannel
        if (channel != null && StationLists.tabOf(channel) == Tab.TV) return true
        return controller?.currentTracks?.isTypeSelected(C.TRACK_TYPE_VIDEO) == true
    }

    private fun showVideo(): Boolean = videoAllowed && isVideoStation() && isPlayingUi()

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun tapListener(action: () -> Unit) = View.OnTouchListener { _, event ->
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) action()
        true
    }

    private fun enterFullscreen() {
        if (!videoAllowed || !showVideo()) return
        fullscreen = true
        backCallback.isEnabled = true
        updateVideoUi()
    }

    private fun exitFullscreen() {
        if (!fullscreen && fullPlayerView.visibility != View.VISIBLE) return
        fullscreen = false
        backCallback.isEnabled = false
        fullPlayerView.visibility = View.GONE
        fullPlayerView.player = null
        updateVideoUi()
    }

    /** Puts the player in the panel or full screen, or shows the logo instead. Gate-checked. */
    private fun updateVideoUi() {
        val show = showVideo()
        if (!show && fullscreen) {
            fullscreen = false
            backCallback.isEnabled = false
        }
        val panel = show && !fullscreen
        playerView.player = if (panel) controller else null
        playerView.visibility = if (panel) View.VISIBLE else View.GONE
        fullPlayerView.player = if (show && fullscreen) controller else null
        fullPlayerView.visibility = if (show && fullscreen) View.VISIBLE else View.GONE
        logoView.visibility = if (panel) View.GONE else View.VISIBLE
        if (shownFrameVideo != panel) {
            shownFrameVideo = panel
            mediaFrame.layoutParams = mediaFrame.layoutParams.apply { width = if (panel) 533 else 300 }
        }
    }

    // ---- Screen text -------------------------------------------------------------------------

    private fun groupLabel(channel: Channel): String = when (StationLists.tabOf(channel)) {
        Tab.MY_LIST -> "MY LIST" + (channel.group?.let { " · $it" } ?: "")
        Tab.TV -> "TV" + (channel.group?.let { " · $it" } ?: "")
        Tab.RADIO -> "RADIO" + (channel.group?.let { " · $it" } ?: "")
    }

    private fun updateStatus() {
        val name = currentChannelName
        val channel = currentChannel
        stationName.text = name ?: "Pick a station"
        stationGroup.text = channel?.let { groupLabel(it) }.orEmpty()

        val programme = guide.now(currentTvgId)
        nowText.text = if (name == null) "" else nowPlaying ?: programme?.let { p ->
            val next = guide.next(currentTvgId)
                ?.let { "  ·  Next ${timeFormat.format(Date(it.start))} ${it.title}" } ?: ""
            "${p.title} (until ${timeFormat.format(Date(p.stop))})$next"
        }.orEmpty()

        val banner = when {
            name != null && serviceStatus != null -> serviceStatus
            message != null -> message
            name != null && stopped -> "Stopped"
            isVideoStation() && !videoAllowed && isPlayingUi() -> "Video available in P. Audio playing."
            else -> null
        }
        bannerText.text = banner.orEmpty()
        bannerText.visibility = if (banner == null) View.GONE else View.VISIBLE

        playButton.setImageResource(if (isPlayingUi()) R.drawable.ic_stop else R.drawable.ic_play)
        val fav = channel != null && channel.url in favourites
        starButton.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
        starButton.setColorFilter(if (fav) getColor(R.color.warn) else getColor(R.color.text))
        starButton.alpha = if (channel == null) 0.4f else 1f

        val logo = channel?.logo
        if (logo != shownLogo || logoView.drawable == null && logo != null) {
            shownLogo = logo
            logoLoader.load(logo, logoView)
        }
        updateVideoUi()
    }

    // ---- Grid --------------------------------------------------------------------------------

    private inner class StationAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false)
            val channel = shown[position]
            view.findViewById<TextView>(R.id.tileName).text = channel.name
            view.findViewById<View>(R.id.tileStar).visibility =
                if (channel.url in favourites) View.VISIBLE else View.GONE
            view.setBackgroundResource(
                if (channel.url == playingUrl) R.drawable.bg_tile_playing else R.drawable.bg_tile,
            )
            logoLoader.load(channel.logo, view.findViewById(R.id.tileLogo))
            return view
        }
    }

    private companion object {
        const val GEAR_POLL_MS = 2_000L
        const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024
        const val PLAYLIST_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val PREF_URL_PREFIX = "playlistUrl_"
        const val PREF_UPDATE_CHECKED = "updateChecked"
        const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val INSTALL_TIMEOUT_MS = 3 * 60 * 1000L
        const val PREF_TAB = "tab"
        const val PREF_LAST_URL = "lastUrl"
        const val PREF_AUTO_RESUME = "autoResume"
        const val PREF_FAVS_ONLY = "favsOnly"
    }
}
