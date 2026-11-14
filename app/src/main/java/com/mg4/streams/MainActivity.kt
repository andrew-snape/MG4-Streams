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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
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
import androidx.media3.common.util.UnstableApi
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

@OptIn(UnstableApi::class)
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
    private lateinit var recentToggle: TextView
    private lateinit var allToggle: TextView
    private lateinit var densityButton: TextView
    private lateinit var driveTitle: TextView
    private lateinit var connChip: TextView
    private lateinit var gridCard: View
    private lateinit var presetRow: LinearLayout
    private lateinit var presetEmpty: TextView
    private lateinit var guideProgress: ProgressBar
    private lateinit var retryButton: TextView
    private lateinit var groupScroll: View
    private lateinit var groupChips: LinearLayout
    private lateinit var dimOverlay: View
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton

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
    private var filter = Filter.ALL
    private var filterChosen = false
    /** Group-title chip selected in the grid; null = all groups. Reset on tab change. */
    private var groupFilter: String? = null
    /** Recently played URLs, newest first (all tabs). */
    private var recents: List<String> = emptyList()
    private var compactGrid = false
    private var shown: List<Channel> = emptyList()
    /** The driving-mode preset row. */
    private var presets: List<Channel> = emptyList()
    private var shownPresetUrls: List<String>? = null
    private var presetKey: Pair<List<String>, Int>? = null
    private var playingUrl: String? = null
    private lateinit var favStore: FavouritesStore
    /** Stations that failed today on this connection: greyed out, skipped by previous/next. */
    private var dead: Set<String> = emptySet()
    private val deadListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { refreshDead() }
    }
    /** Cover art: separate cache in cacheDir (many small images), pruned at start. */
    private lateinit var artLoader: LogoLoader
    private val adapter = StationAdapter()
    /** Driving layout: shown whenever video is not allowed (not P, or gear unknown). */
    private var drivingUi: Boolean? = null
    private var netKind = NetKind.NONE
    private lateinit var connectivity: android.net.ConnectivityManager
    private val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = netChanged()
        override fun onLost(network: android.net.Network) = netChanged()
        override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = netChanged()
    }

    private var fullscreen = false
    private var settingsDialog: AlertDialog? = null
    private var shownLogo: String? = null
    private var shownFrameVideo: Boolean? = null
    private val timeFormat: DateFormat by lazy { android.text.format.DateFormat.getTimeFormat(this) }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitFullscreen()
    }

    /** Once a minute: guide text and progress, night dim. */
    private val guideTick = object : Runnable {
        override fun run() {
            if (!guide.isEmpty) updateStatus()
            updateDim()
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

    private val updater by lazy { Updater(applicationContext) }
    private var updateRunning = false
    /** Update check and download, so they never hold up playlist work on [worker]. */
    private val updateThread = Executors.newSingleThreadExecutor()
    /** The release waiting for the storage permission answer. */
    private var pendingRelease: Updater.Release? = null

    /** Storage permission puts the update in the public Downloads folder; without it, the app's own. */
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            pendingRelease?.let { release ->
                pendingRelease = null
                downloadUpdate(release)
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
            urlOf(mediaItem)?.let {
                prefs.edit().putString(PREF_LAST_URL, it).apply()
                addRecent(it)
            }
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
        val artDir = File(cacheDir, "art")
        artLoader = LogoLoader(artDir, logoThreads, handler)
        worker.execute { pruneArt(artDir) }
        DeadStations.prefs(this).registerOnSharedPreferenceChangeListener(deadListener)
        dead = DeadStations.current(this)

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
        recentToggle = findViewById(R.id.recentToggle)
        allToggle = findViewById(R.id.allToggle)
        densityButton = findViewById(R.id.densityButton)
        driveTitle = findViewById(R.id.driveTitle)
        connChip = findViewById(R.id.connChip)
        gridCard = findViewById(R.id.gridCard)
        presetRow = findViewById(R.id.presetRow)
        presetEmpty = findViewById(R.id.presetEmpty)
        guideProgress = findViewById(R.id.guideProgress)
        retryButton = findViewById(R.id.retryButton)
        groupScroll = findViewById(R.id.groupScroll)
        groupChips = findViewById(R.id.groupChips)
        dimOverlay = findViewById(R.id.dimOverlay)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        tabViews = mapOf(
            Tab.RADIO to findViewById(R.id.tabRadio),
            Tab.TV to findViewById(R.id.tabTv),
            Tab.MY_LIST to findViewById(R.id.tabMy),
        )

        tab = Tab.fromName(prefs.getString(PREF_TAB, null))
        // Older versions saved a favourites-only boolean; keep honouring it.
        val savedFilter = Filter.fromName(prefs.getString(PREF_FILTER, null))
        filterChosen = savedFilter != null || prefs.contains(PREF_FAVS_ONLY)
        filter = savedFilter ?: if (prefs.getBoolean(PREF_FAVS_ONLY, false)) Filter.FAVOURITES else Filter.ALL
        recents = prefs.getString(PREF_RECENTS, null).orEmpty().lines().filter { it.isNotBlank() }
        compactGrid = prefs.getBoolean(PREF_COMPACT, false)
        applyDensity()

        tabViews.forEach { (t, view) ->
            view.setOnClickListener {
                tab = t
                groupFilter = null
                prefs.edit().putString(PREF_TAB, t.name).apply()
                refreshShown()
            }
        }
        favsToggle.setOnClickListener { chooseFilter(Filter.FAVOURITES) }
        recentToggle.setOnClickListener { chooseFilter(Filter.RECENT) }
        allToggle.setOnClickListener { chooseFilter(Filter.ALL) }
        densityButton.setOnClickListener {
            compactGrid = !compactGrid
            prefs.edit().putBoolean(PREF_COMPACT, compactGrid).apply()
            applyDensity()
        }
        retryButton.setOnClickListener { retry() }

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
        searchInput.setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        settingsButton.setOnClickListener { showSettings() }

        playButton.setOnClickListener { if (isPlayingUi()) stopPlayback() else resume() }
        prevButton.setOnClickListener { stepStation(-1) }
        nextButton.setOnClickListener { stepStation(1) }
        starButton.setOnClickListener { currentChannel?.let { toggleFavourite(it) } }
        // PlayerView ignores clicks when its controller is off, so use a touch listener.
        playerView.setOnTouchListener(tapListener { enterFullscreen() })
        fullPlayerView.setOnTouchListener(tapListener { exitFullscreen() })
        // Swipe the logo left/right for the next/previous station.
        val swipe = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (Math.abs(dx) < SWIPE_MIN_PX || Math.abs(vx) < Math.abs(vy)) return false
                stepStation(if (dx < 0) 1 else -1)
                return true
            }
        })
        @Suppress("ClickableViewAccessibility")
        logoView.setOnTouchListener { _, event -> swipe.onTouchEvent(event) }

        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        runCatching { connectivity.registerDefaultNetworkCallback(netCallback, handler) }
        netKind = NetKind.current(this)

        updateParkedUi()
        gearGate.connect(this)
        loadFavourites()
        loadPlaylists()
        updateStatus()
        updateDim()
    }

    override fun onStart() {
        super.onStart()
        gateGeneration++
        setVideoAllowed(false)
        connectToService()
    }

    private fun netChanged() {
        val kind = NetKind.current(this)
        if (kind == netKind) return
        netKind = kind
        refreshDead()
        updateStatus()
    }

    /** Re-reads the dead stations (they depend on the connection) and greys tiles to match. */
    private fun refreshDead() {
        val now = DeadStations.current(this)
        if (now == dead) return
        dead = now
        adapter.notifyDataSetChanged()
        markPresets()
    }

    /** Keeps the newest cover images only. */
    private fun pruneArt(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_ART_FILES).forEach { it.delete() }
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
        runCatching { connectivity.unregisterNetworkCallback(netCallback) }
        DeadStations.prefs(this).unregisterOnSharedPreferenceChangeListener(deadListener)
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
        // Auto-resume is on by default, but never for TV (a car start shouldn't start TV audio).
        val resume = prefs.getBoolean(PREF_AUTO_RESUME, true) && StationLists.tabOf(channel) != Tab.TV
        if (resume) playChannel(channel, presetsOrNull(channel)) else updateStatus()
    }

    /** The preset list if [channel] is in it (so steering-wheel next/previous steps presets). */
    private fun presetsOrNull(channel: Channel): List<Channel>? =
        presets.takeIf { list -> drivingUi == true && list.any { it.url == channel.url } }

    // ---- Lists, tabs, favourites -------------------------------------------------------------

    private fun loadFavourites() {
        worker.execute {
            val loaded = favStore.load()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                favourites.clear()
                favourites.addAll(loaded)
                // Default to Favourites once there are any, until the user chooses.
                if (!filterChosen) filter = if (favourites.isNotEmpty()) Filter.FAVOURITES else Filter.ALL
                refreshShown()
                updateStatus()
            }
        }
    }

    private fun chooseFilter(newFilter: Filter) {
        filter = newFilter
        filterChosen = true
        prefs.edit().putString(PREF_FILTER, newFilter.name).apply()
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

    private fun addRecent(url: String) {
        if (recents.firstOrNull() == url) return
        recents = StationLists.pushRecent(recents, url)
        prefs.edit().putString(PREF_RECENTS, recents.joinToString("\n")).apply()
        if (filter == Filter.RECENT && drivingUi != true) refreshShown()
    }

    private fun applyDensity() {
        stationGrid.numColumns = if (compactGrid) 5 else 3
        densityButton.text = if (compactGrid) "3×" else "5×"
        adapter.notifyDataSetChanged()
    }

    /** Recomputes the grid (and the preset row) from the tab, search, filter and group. */
    private fun refreshShown() {
        val groups = if (filter == Filter.RECENT) emptyList() else StationLists.groups(channels, tab)
        if (groupFilter != null && groupFilter !in groups) groupFilter = null
        shown = StationLists.filter(channels, tab, query, favourites, filter, recents, groupFilter)
        adapter.notifyDataSetChanged()
        tabViews.forEach { (t, view) -> view.isSelected = t == tab && filter != Filter.RECENT }
        favsToggle.isSelected = filter == Filter.FAVOURITES
        recentToggle.isSelected = filter == Filter.RECENT
        allToggle.isSelected = filter == Filter.ALL
        gridTitle.text = if (filter == Filter.RECENT) "RECENT · ALL TABS"
        else "${tab.label.uppercase()} · ${filter.label}" + (groupFilter?.let { " · ${it.uppercase()}" } ?: "")
        updateGroupChips(groups)
        val empty = when {
            shown.isNotEmpty() -> null
            !listsLoaded -> "Loading stations..."
            query.isNotBlank() -> "No stations match \"${query.trim()}\""
            filter == Filter.RECENT -> "Nothing played yet."
            filter == Filter.FAVOURITES -> "No favourites here yet.\nLong-press a station to add one."
            else -> "No ${tab.label} playlist yet.\nAdd one in Settings (when parked)."
        }
        emptyText.text = empty.orEmpty()
        emptyText.visibility = if (empty == null) View.GONE else View.VISIBLE
        refreshPresets()
    }

    /** Group chips above the grid when the tab has two or more groups. */
    private fun updateGroupChips(groups: List<String>) {
        if (groups.size < 2) {
            groupScroll.visibility = View.GONE
            groupChips.removeAllViews()
            return
        }
        groupScroll.visibility = View.VISIBLE
        groupChips.removeAllViews()
        (listOf<String?>(null) + groups).forEach { group ->
            val chip = TextView(this).apply {
                text = group ?: "All groups"
                textSize = 20f
                setTextColor(getColor(R.color.text))
                gravity = android.view.Gravity.CENTER
                setPadding(28, 0, 28, 0)
                setBackgroundResource(R.drawable.bg_tab)
                isSelected = group == groupFilter
                setOnClickListener {
                    groupFilter = group
                    refreshShown()
                }
            }
            groupChips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 56).apply { marginEnd = 10 })
        }
    }

    /** The six driving-mode presets: favourites (any tab), then recently played. */
    private fun refreshPresets() {
        // While driving, keep the row's order fixed: recents reorder on every station change,
        // which would move tiles under the driver's finger and break next/previous. Recompute
        // only when parked, when favourites change, or when the playlists change.
        val key = favourites.toList() to channels.size
        if (drivingUi != true || presets.isEmpty() || key != presetKey) {
            presets = StationLists.presets(channels, favourites, recents, PRESET_COUNT)
            presetKey = key
        }
        val urls = presets.map { it.url }
        if (urls != shownPresetUrls) {
            shownPresetUrls = urls
            presetRow.removeAllViews()
            val inflater = LayoutInflater.from(this)
            presets.forEach { channel ->
                val tile = inflater.inflate(R.layout.item_preset, presetRow, false)
                tile.findViewById<TextView>(R.id.presetName).text = channel.name
                logoLoader.load(channel.logo, tile.findViewById(R.id.presetLogo))
                tile.setOnClickListener { playChannel(channel, presets) }
                tile.tag = channel.url
                presetRow.addView(tile)
            }
        }
        markPresets()
        updateDrivingUi()
    }

    /** Highlights the playing preset and greys out dead ones. */
    private fun markPresets() {
        for (i in 0 until presetRow.childCount) {
            val tile = presetRow.getChildAt(i)
            tile.isSelected = tile.tag == playingUrl
            tile.alpha = if (tile.tag in dead && tile.tag != playingUrl) DEAD_ALPHA else 1f
        }
    }

    /**
     * Settings > Arrange presets: favourites in order; the first six are the driving presets.
     * Up/down moves a station; the order is saved with the favourites.
     */
    private fun showArrangePresets() {
        val order = favourites.toMutableList()
        if (order.isEmpty()) {
            Toast.makeText(this, "No favourites yet. Long-press a station to add one.", Toast.LENGTH_LONG).show()
            return
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        fun save() {
            favourites.clear()
            favourites.addAll(order)
            val snapshot = order.toList()
            worker.execute { runCatching { favStore.save(snapshot) } }
            refreshPresets()
            refreshShown()
        }
        fun render() {
            list.removeAllViews()
            order.forEachIndexed { i, url ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val label = TextView(this).apply {
                    val name = channelsByUrl[url]?.name ?: url.substringAfter("://").take(40)
                    text = if (i < PRESET_COUNT) "${i + 1}.  $name" else "     $name"
                    textSize = 22f
                    setTextColor(getColor(if (i < PRESET_COUNT) R.color.text else R.color.text_dim))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                row.addView(label, LinearLayout.LayoutParams(0, 88, 1f))
                fun arrow(textValue: String, enabled: Boolean, move: Int) = Button(this).apply {
                    text = textValue
                    textSize = 26f
                    isEnabled = enabled
                    setOnClickListener {
                        java.util.Collections.swap(order, i, i + move)
                        render()
                        save()
                    }
                }
                row.addView(arrow("▲", i > 0, -1), LinearLayout.LayoutParams(110, 88))
                row.addView(arrow("▼", i < order.size - 1, 1), LinearLayout.LayoutParams(110, 88))
                list.addView(row)
                if (i == PRESET_COUNT - 1 && order.size > PRESET_COUNT) {
                    list.addView(TextView(this).apply {
                        text = "Not in the preset row:"
                        textSize = 18f
                        setTextColor(getColor(R.color.text_dim))
                        setPadding(0, 16, 0, 8)
                    })
                }
            }
        }
        render()
        AlertDialog.Builder(this)
            .setTitle("Arrange presets (first $PRESET_COUNT shown while driving)")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
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

    /**
     * Each slot's playlist, read and parsed, kept apart. Runs on the worker thread, so parsing a
     * few hundred channels never holds up the screen.
     */
    private fun readSavedLists(): Map<Tab, Playlist> =
        slotFiles.mapValues { (_, file) -> M3uParser.parsePlaylist(readOrEmpty(file)) }

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
    private fun setPlaylistText(parsed: Map<Tab, Playlist>, forceGuide: Boolean = false) {
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
        // Grows as needed: a typical playlist is 50-100 KB, so don't allocate 8 MB up front.
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            out.write(chunk, 0, read)
            if (out.size() > MAX_PLAYLIST_BYTES) throw IllegalStateException("Playlist is larger than 8 MB")
        }
        return out.toString(Charsets.UTF_8.name())
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

    private fun showSettings() {
        if (settingsDialog != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val urlInput = view.findViewById<EditText>(R.id.urlInput)
        val autoSwitch = view.findViewById<Switch>(R.id.autoResumeSwitch)
        autoSwitch.isChecked = prefs.getBoolean(PREF_AUTO_RESUME, true)
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_AUTO_RESUME, checked).apply()
        }
        val dimSwitch = view.findViewById<Switch>(R.id.nightDimSwitch)
        dimSwitch.isChecked = prefs.getBoolean(PREF_NIGHT_DIM, true)
        dimSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_NIGHT_DIM, checked).apply()
            updateDim()
        }
        val simpleSwitch = view.findViewById<Switch>(R.id.simpleDrivingSwitch)
        simpleSwitch.isChecked = prefs.getBoolean(PREF_SIMPLE_DRIVING, false)
        simpleSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_SIMPLE_DRIVING, checked).apply()
            refreshPresets() // also re-applies the layout
        }
        val artSwitch = view.findViewById<Switch>(R.id.albumArtSwitch)
        artSwitch.isChecked = prefs.getBoolean(PlaybackService.PREF_ALBUM_ART, true)
        artSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PlaybackService.PREF_ALBUM_ART, checked).apply()
            updateStatus()
        }
        view.findViewById<TextView>(R.id.dataUsageText).text = DataUsage.summary(this)
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
        view.findViewById<Button>(R.id.arrangePresetsButton).setOnClickListener {
            dialog.dismiss()
            showArrangePresets()
        }
        view.findViewById<Button>(R.id.netCheckButton).setOnClickListener {
            dialog.dismiss()
            runNetworkCheck()
        }
        dialog.setOnDismissListener { settingsDialog = null }
        settingsDialog = dialog
        dialog.show()
    }

    // ---- Network check -----------------------------------------------------------------------

    private var netCheckRunning = false

    /** Tests the connection and the station on screen, then shows the report (parked only). */
    private fun runNetworkCheck() {
        if (netCheckRunning) return
        netCheckRunning = true
        message = "Checking the network..."
        updateStatus()
        val channel = currentChannel
        // Its own short-lived thread, so a slow server can't hold up playlists or updates.
        Thread {
            val report = runCatching { NetCheck.run(applicationContext, channel) }
                .getOrElse { "Network check failed: ${it.message}" }
            android.util.Log.i("MG4Streams", "Network check\n$report")
            runOnUiThread {
                netCheckRunning = false
                if (isDestroyed) return@runOnUiThread
                message = null
                updateStatus()
                AlertDialog.Builder(this)
                    .setTitle("Network check")
                    .setMessage(report)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }.apply { isDaemon = true; name = "netcheck" }.start()
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
            .setPositiveButton("Download") { _, _ -> startUpdate(release) }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Asks for storage permission once (so the APK lands in Downloads), then downloads. */
    private fun startUpdate(release: Updater.Release) {
        if (updateRunning) return
        val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            downloadUpdate(release)
        } else {
            pendingRelease = release
            storagePermission.launch(permission)
        }
    }

    /**
     * Downloads the release APK into Downloads, checks it (package, version, signing key), then
     * opens Downloads so the user can tap it. Android's installer does the install: the head unit
     * refuses installs started by the app itself.
     */
    private fun downloadUpdate(release: Updater.Release) {
        if (updateRunning) return
        updateRunning = true
        message = "Downloading update..."
        updateStatus()
        updateThread.execute {
            try {
                val download = updater.download(release) { percent ->
                    if (percent >= 0 && percent % 5 == 0) runOnUiThread {
                        if (!isDestroyed) { message = "Downloading update... $percent%"; updateStatus() }
                    }
                }
                updater.verify(download.file)?.let { reason ->
                    updater.discard(download)
                    throw IllegalStateException(reason)
                }
                runOnUiThread {
                    updateRunning = false
                    if (isDestroyed) return@runOnUiThread
                    showInstallSteps(download.file.name)
                }
            } catch (e: InterruptedException) {
                // The screen closed; DownloadManager finishes the download on its own.
            } catch (e: Exception) {
                runOnUiThread {
                    updateRunning = false
                    if (!isDestroyed) { message = "Update failed: ${e.message}"; updateStatus() }
                }
            }
        }
    }

    private fun showInstallSteps(fileName: String) {
        message = "Update downloaded: tap $fileName in Downloads to install"
        updateStatus()
        AlertDialog.Builder(this)
            .setTitle("Update downloaded")
            .setMessage(
                "Downloads opens next. Tap $fileName, then Install.\n\n" +
                    "Playback stops while it installs. Open MG4 Streams again afterwards.",
            )
            .setPositiveButton("Open Downloads") { _, _ ->
                if (!updater.openDownloads()) {
                    message = "Couldn't open Downloads. Open $fileName from the Files app."
                    updateStatus()
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Search and settings are usable only while parked. While driving they're hidden. */
    private fun updateParkedUi() = updateDrivingUi()

    /**
     * The station grid, tabs, search and Settings stay available while driving; only video
     * needs P. If "Simple driving screen" is on in Settings, then whenever video isn't allowed the
     * grid, search, settings and tabs give way to a row of six presets with bigger text and
     * previous/next buttons.
     */
    private fun updateDrivingUi() {
        val driving = !videoAllowed && prefs.getBoolean(PREF_SIMPLE_DRIVING, false)
        val presetsVisible = driving && presets.isNotEmpty()
        presetRow.visibility = if (presetsVisible) View.VISIBLE else View.GONE
        presetEmpty.visibility = if (driving && presets.isEmpty()) View.VISIBLE else View.GONE
        if (drivingUi == driving) return
        drivingUi = driving
        val parkedOnly = if (driving) View.GONE else View.VISIBLE
        gridCard.visibility = parkedOnly
        searchInput.visibility = parkedOnly
        settingsButton.visibility = parkedOnly
        tabViews.values.forEach { it.visibility = parkedOnly }
        driveTitle.visibility = if (driving) View.VISIBLE else View.GONE
        stationName.textSize = if (driving) 48f else 42f
        stationName.maxLines = if (driving) 1 else 2
        nowText.textSize = if (driving) 28f else 24f
        nowText.maxLines = if (driving) 1 else 3
        bannerText.maxLines = if (driving) 2 else 3
        retryButton.layoutParams = retryButton.layoutParams.apply { height = if (driving) 64 else 88 }
        val control = if (driving) DRIVING_CONTROL_PX else PARKED_CONTROL_PX
        listOf(prevButton, nextButton).forEach { button ->
            button.layoutParams = button.layoutParams.apply { width = control; height = control }
        }
        shownFrameVideo = null // re-size the logo frame for the new mode
        updateVideoUi()
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
                    .setArtworkUri(channel.logo?.let(Uri::parse))
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
        addRecent(channel.url)
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
        // While driving, previous/next step through the presets (the grid is hidden).
        val list = if (drivingUi == true && presets.isNotEmpty()) presets else shown
        val current = currentChannel?.url ?: playingUrl
        var target = StationLists.step(list, current, delta)
        if (target < 0) return
        // Skip stations that failed today on this connection (if any other station is left).
        if (list[target].url in dead) {
            val alive = DeadStations.nextAlive(list.size, target, if (delta > 0) 1 else -1) {
                list[it].url in dead && list[it].url != current
            }
            if (alive >= 0) target = alive
        }
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
            // Parked video 640 x 360 (16:9); parked logo 300; driving logo 160 (room for presets).
            val (w, h) = when {
                panel -> 640 to 360
                drivingUi == true -> 160 to 160
                else -> 300 to 300
            }
            mediaFrame.layoutParams = mediaFrame.layoutParams.apply { width = w; height = h }
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
        val shownBanner = banner?.let { it + hintFor(it) }
        // Driving: the banner replaces the now-playing line, so the controls keep their room.
        if (drivingUi == true && shownBanner != null) nowText.text = ""
        nowText.visibility = if (nowText.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        bannerText.text = shownBanner.orEmpty()
        bannerText.visibility = if (shownBanner == null) View.GONE else View.VISIBLE
        retryButton.visibility = if (name != null && !stopped && isFailed()) View.VISIBLE else View.GONE

        // Programme progress (TV guide), parked only.
        if (programme != null && nowPlaying == null && drivingUi != true && programme.stop > programme.start) {
            val elapsed = System.currentTimeMillis() - programme.start
            guideProgress.progress = (elapsed * 1000 / (programme.stop - programme.start)).toInt().coerceIn(0, 1000)
            guideProgress.visibility = View.VISIBLE
        } else {
            guideProgress.visibility = View.GONE
        }
        updateConnChip()
        markPresets()

        playButton.setImageResource(if (isPlayingUi()) R.drawable.ic_stop else R.drawable.ic_play)
        val fav = channel != null && channel.url in favourites
        starButton.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
        starButton.setColorFilter(if (fav) getColor(R.color.warn) else getColor(R.color.text))
        starButton.alpha = if (channel == null) 0.4f else 1f

        // Cover art for the song playing (set by PlaybackService), otherwise the station logo.
        val art = controller?.mediaMetadata?.artworkUri?.toString()
            ?.takeIf { nowPlaying != null && it != channel?.logo && prefs.getBoolean(PlaybackService.PREF_ALBUM_ART, true) }
        val logo = art ?: channel?.logo
        if (logo != shownLogo || logoView.drawable == null && logo != null) {
            shownLogo = logo
            (if (art != null) artLoader else logoLoader).load(logo, logoView)
        }
        updateVideoUi()
    }

    /** True when playback has given up (an error is showing and nothing is retrying). */
    private fun isFailed(): Boolean {
        val status = serviceStatus
        if (status != null) return status.startsWith("Can't play") || status.startsWith("No internet")
        val player = controller ?: return false
        return player.playerError != null && player.playbackState == Player.STATE_IDLE
    }

    private fun retry() {
        val channel = currentChannel ?: return
        if (controller?.currentMediaItem != null) resume() else playChannel(channel)
    }

    /**
     * A plain-language next step for an error. A refusal (HTTP 401/403) on the car's internet is
     * usually the broadcaster blocking that network: suggest a hotspot.
     */
    private fun hintFor(text: String): String {
        val refused = text.contains("HTTP 403") || text.contains("HTTP 401")
        if (!refused) return ""
        val channel = currentChannel ?: return ""
        return when {
            netKind == NetKind.CAR -> " This channel blocks the car's internet. Use a phone hotspot or Wi-Fi for it."
            else -> ""
        }
    }

    /** Top-right chip: the connection, amber while reconnecting. */
    private fun updateConnChip() {
        val status = serviceStatus
        val reconnecting = status != null && (status.startsWith("Reconnecting") || status.startsWith("Waiting"))
        connChip.text = when {
            reconnecting -> "Reconnecting…"
            else -> netKind.label
        }
        connChip.setTextColor(
            getColor(
                when {
                    reconnecting -> R.color.warn
                    netKind == NetKind.NONE -> R.color.warn
                    else -> R.color.text_dim
                },
            ),
        )
    }

    /** Night dim: a dark overlay from 7 pm to 6 am (setting, on by default). */
    private fun updateDim() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val night = hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
        val dim = night && prefs.getBoolean(PREF_NIGHT_DIM, true)
        dimOverlay.visibility = if (dim) View.VISIBLE else View.GONE
    }

    // ---- Grid --------------------------------------------------------------------------------

    private inner class StationAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false)
                    .also { it.tag = TileViews(it) }
            val tile = view.tag as TileViews
            val channel = shown[position]
            if (tile.compact != compactGrid) {
                // 5 columns: smaller logo and one line of name, so tiles fit the card width.
                tile.compact = compactGrid
                val side = if (compactGrid) 64 else 110
                (tile.logo.parent as View).layoutParams = (tile.logo.parent as View).layoutParams.apply { width = side; height = side }
                tile.name.textSize = if (compactGrid) 14f else 17f
                tile.name.setLines(if (compactGrid) 1 else 2)
                view.setPadding(if (compactGrid) 4 else 10, 10, if (compactGrid) 4 else 10, 10)
            }
            tile.name.text = channel.name
            tile.star.visibility = if (channel.url in favourites) View.VISIBLE else View.GONE
            val playing = channel.url == playingUrl
            if (tile.playing != playing) {
                tile.playing = playing
                view.setBackgroundResource(if (playing) R.drawable.bg_tile_playing else R.drawable.bg_tile)
            }
            view.alpha = if (channel.url in dead && !playing) DEAD_ALPHA else 1f
            logoLoader.load(channel.logo, tile.logo)
            return view
        }
    }

    /** A grid tile's views, looked up once per tile instead of on every scroll or refresh. */
    private class TileViews(view: View) {
        val name: TextView = view.findViewById(R.id.tileName)
        val star: View = view.findViewById(R.id.tileStar)
        val logo: ImageView = view.findViewById(R.id.tileLogo)
        var playing: Boolean? = null
        var compact: Boolean? = null
    }

    private companion object {
        const val GEAR_POLL_MS = 2_000L
        const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024
        const val PLAYLIST_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        const val PREF_URL_PREFIX = "playlistUrl_"
        const val PREF_UPDATE_CHECKED = "updateChecked"
        const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val PREF_TAB = "tab"
        const val PREF_LAST_URL = "lastUrl"
        const val PREF_AUTO_RESUME = "autoResume"
        const val PREF_FAVS_ONLY = "favsOnly"
        const val PREF_FILTER = "filter"
        const val PREF_RECENTS = "recents"
        const val PREF_COMPACT = "compactGrid"
        const val PREF_NIGHT_DIM = "nightDim"
        const val PREF_SIMPLE_DRIVING = "simpleDriving"
        const val PRESET_COUNT = 6
        const val DRIVING_CONTROL_PX = 110
        const val NIGHT_START_HOUR = 19
        const val NIGHT_END_HOUR = 6
        const val PARKED_CONTROL_PX = 88
        const val SWIPE_MIN_PX = 120f
        const val DEAD_ALPHA = 0.35f
        const val MAX_ART_FILES = 60
    }
}
