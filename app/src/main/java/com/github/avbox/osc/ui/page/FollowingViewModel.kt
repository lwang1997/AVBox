package com.github.avbox.osc.ui.page

import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.api.AppBootstrap
import com.github.avbox.osc.api.FollowTmdbRefresh
import com.github.avbox.osc.api.SiteHealthChecker
import com.github.avbox.osc.api.TmdbPoster
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.bean.AbsXml
import com.github.avbox.osc.bean.FollowTmdbInfo
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.bean.SiteHealth
import com.github.avbox.osc.bean.TmdbPosterCache
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.data.AppGraph
import com.github.avbox.osc.data.EpisodeTotals
import com.github.avbox.osc.data.FollowDays
import com.github.avbox.osc.data.FollowRepository
import com.github.avbox.osc.data.FollowSiteInfo
import com.github.avbox.osc.data.FollowWatched
import com.github.avbox.osc.data.HistoryRepository
import com.github.avbox.osc.data.PlaybackProgress
import com.github.avbox.osc.data.VodFollow
import com.github.avbox.osc.event.UiChange
import com.github.avbox.osc.event.UiEvents
import com.github.avbox.osc.sourcedata.SourceViewModel
import com.github.avbox.osc.util.HistoryHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class FollowEntry(
    val follow: VodFollow,
    val history: VodInfo?,
    val watchedDays: Set<Int>,
)

internal data class RebindSite(val key: String, val name: String)

internal data class RebindState(
    val follow: VodFollow,
    val sites: List<RebindSite>,
    val siteKey: String? = null,
    val siteName: String? = null,
    val searching: Boolean = false,
    val results: List<Movie.Video> = emptyList(),
)

internal class FollowingViewModel(
    private val follows: FollowRepository = AppGraph.followRepository,
    private val history: HistoryRepository = AppGraph.historyRepository,
) : ViewModel(), TmdbPosterCache {

    override fun tmdbCachedPoster(name: String?, year: Int, altName: String?): String? =
        TmdbPoster.cachedPoster(name, year, altName)

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _items = MutableStateFlow<List<FollowEntry>>(emptyList())
    val items = _items.asStateFlow()
    private val entries = MutableStateFlow<List<FollowEntry>>(emptyList())
    private val _episodeTotals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val episodeTotals = _episodeTotals.asStateFlow()
    private val _playedPercents = MutableStateFlow<Map<String, Int>>(emptyMap())
    val playedPercents = _playedPercents.asStateFlow()
    private val _today = MutableStateFlow(FollowDays.todayIndex())
    val today = _today.asStateFlow()

    private val _selectedDay = MutableStateFlow<Int?>(null)
    val selectedDay = _selectedDay.asStateFlow()
    private val _editMode = MutableStateFlow(false)
    val editMode = _editMode.asStateFlow()
    private val _selected = MutableStateFlow<Set<Int>>(emptySet())
    val selected = _selected.asStateFlow()

    private val historyTick = MutableStateFlow(0)

    private val _rebind = MutableStateFlow<RebindState?>(null)
    val rebind = _rebind.asStateFlow()
    private val searchCaller = SourceViewModel()
    private var rebindToken = 0
    private var rebindWaiter: CompletableDeferred<Unit>? = null

    private val _siteTotals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val siteTotals = _siteTotals.asStateFlow()

    /** 站点质量，与首页站点列表同源。 */
    val siteHealth: StateFlow<Map<String, SiteHealth>> get() = SiteHealthChecker.results
    private val siteCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private var siteDetailToken = 0
    private var siteDetailWaiter: CompletableDeferred<AbsXml?>? = null

    init {
        observeHistoryChanges()
        viewModelScope.launch {
            searchCaller.searchResult.flow.collect { onRebindSearchResult(it) }
        }
        viewModelScope.launch {
            searchCaller.detailResult.flow.collect { data ->
                val waiter = siteDetailWaiter ?: return@collect
                if (data != null && data.detailToken == siteDetailToken) waiter.complete(data)
            }
        }
        _siteTotals.value = FollowSiteInfo.totals()
        viewModelScope.launch(Dispatchers.IO) {
            combine(follows.observeAll(), historyTick) { list, _ -> list }
                .map { list -> list.map { follow -> FollowEntry(follow, resolveHistory(follow), emptySet()) } }
                .collect {
                    entries.value = it
                    _episodeTotals.value = EpisodeTotals.snapshot()
                }
        }
        viewModelScope.launch(Dispatchers.IO) {
            combine(entries, FollowWatched.state) { list, watched ->
                list.map { entry ->
                    entry.copy(
                        watchedDays = watched[FollowWatched.ownerOf(entry.follow.sourceKey, entry.follow.vodId)].orEmpty(),
                    )
                }
            }.collect {
                _items.value = it
                _loading.value = false
                maybeCheckSiteUpdates()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            PlaybackProgress.percents.collect { map ->
                _playedPercents.value = if (HistoryHelper.isIncognito()) emptyMap() else map
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            AppBootstrap.subscriptionCid.drop(1).collect { historyTick.value++ }
        }
        viewModelScope.launch {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) historyTick.value++
            }
        }
    }

    private fun observeHistoryChanges() {
        viewModelScope.launch {
            UiEvents.changes.collect { change ->
                if (change is UiChange.HistoryChanged) historyTick.value++
            }
        }
    }

    fun refresh() {
        _today.value = FollowDays.todayIndex()
        historyTick.value++
        maybeCheckSiteUpdates()
    }

    /** 下拉刷新：强制重拉 TMDB 更新信息，完成后重载本地列表。 */
    fun refreshFromPull() {
        if (_refreshing.value) return
        _refreshing.value = true
        maybeCheckSiteUpdates(force = true)
        refresh()
        FollowTmdbRefresh.refreshNow {
            refresh()
            _refreshing.value = false
        }
    }

    /**
     * 没有 TMDB 更新信息的追剧，从绑定站点拉详情核对集数。
     * 每条按 [SITE_CHECK_INTERVAL_MS] 节流，逐条请求避免同时打满站点。
     */
    fun maybeCheckSiteUpdates(force: Boolean = false) {
        if (HistoryHelper.isIncognito()) return
        val follows = _items.value.map { it.follow }
        if (follows.none { needsSiteUpdate(it) }) return
        if (!siteCheckRunning.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                for (follow in follows) {
                    if (!needsSiteUpdate(follow)) continue
                    val owner = EpisodeTotals.key(follow.sourceKey, follow.vodId)
                    val now = System.currentTimeMillis()
                    if (!force && now - FollowSiteInfo.checkedAt(owner) < SITE_CHECK_INTERVAL_MS) continue
                    checkSiteEpisodes(follow)
                    _siteTotals.value = FollowSiteInfo.totals()
                    delay(SITE_CHECK_GAP_MS)
                }
            } finally {
                siteCheckRunning.set(false)
            }
        }
    }

    private fun needsSiteUpdate(follow: VodFollow): Boolean {
        if (follow.sourceKey.isNullOrBlank() || follow.vodId.isNullOrBlank()) return false
        val tmdb = FollowTmdbInfo.decode(follow.tmdbInfo)
        return tmdb == null || (tmdb.displayUpdateEpisode <= 0 && tmdb.totalEpisodes <= 0)
    }

    private suspend fun checkSiteEpisodes(follow: VodFollow) {
        val sourceKey = follow.sourceKey ?: return
        val vodId = follow.vodId ?: return
        val owner = EpisodeTotals.key(sourceKey, vodId)
        val token = ++siteDetailToken
        val waiter = CompletableDeferred<AbsXml?>()
        siteDetailWaiter = waiter
        try {
            searchCaller.getDetail(sourceKey, vodId, false, token)
            val data = withTimeoutOrNull(SITE_DETAIL_TIMEOUT_MS) { waiter.await() }
            val video = data?.movie?.videoList?.firstOrNull()
            if (video != null) {
                val vod = VodInfo.fromVideo(video)
                val total = vod.seriesMap.orEmpty().values
                    .mapNotNull { EpisodeTotals.episodeCount(it.map { series -> series.name }) }
                    .maxOrNull()
                if (total != null) {
                    FollowSiteInfo.putTotal(owner, total)
                    EpisodeTotals.put(sourceKey, vodId, total)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            // 单条站点失败不影响其余追剧
        } finally {
            if (siteDetailWaiter === waiter) siteDetailWaiter = null
            FollowSiteInfo.markChecked(owner, System.currentTimeMillis())
        }
    }

    private suspend fun resolveHistory(follow: VodFollow): VodInfo? =
        if (HistoryHelper.isIncognito()) {
            null
        } else {
            history.getVodInfo(follow.sourceKey.orEmpty(), follow.vodId.orEmpty())
        }

    fun setWatched(id: Int, watched: Boolean) {
        val entry = _items.value.firstOrNull { it.follow.id == id } ?: return
        val days = targetDays(entry)
        if (days.isEmpty()) return
        val owner = FollowWatched.ownerOf(entry.follow.sourceKey, entry.follow.vodId)
        val updated = if (watched) entry.watchedDays + days else entry.watchedDays - days
        _items.value = _items.value.map { if (it.follow.id == id) it.copy(watchedDays = updated) else it }
        viewModelScope.launch(Dispatchers.IO) {
            val week = FollowWatched.weekStart()
            if (watched) {
                FollowWatched.mark(owner, days, week)
            } else {
                FollowWatched.clear(owner, days, week)
            }
        }
    }

    private fun targetDays(entry: FollowEntry): Set<Int> =
        _selectedDay.value?.let { setOf(it) } ?: FollowDays.decode(entry.follow.updateDays)

    fun selectDay(day: Int?) {
        _selectedDay.value = day
    }

    fun enterEdit(id: Int? = null) {
        _editMode.value = true
        _selected.value = if (id == null) emptySet() else setOf(id)
    }

    fun exitEdit() {
        _editMode.value = false
        _selected.value = emptySet()
    }

    fun toggleSelected(id: Int) {
        _selected.value = if (id in _selected.value) _selected.value - id else _selected.value + id
    }

    fun pruneSelection(ids: Set<Int>) {
        val pruned = _selected.value.intersect(ids)
        if (pruned.size != _selected.value.size) _selected.value = pruned
        if (ids.isEmpty()) _editMode.value = false
    }

    fun deleteSelected() {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        exitEdit()
        viewModelScope.launch(Dispatchers.IO) {
            follows.deleteSelected(ids)
        }
    }

    fun startRebind(follow: VodFollow) {
        val sites = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() }
            .map { RebindSite(it.key.orEmpty(), it.name.orEmpty()) }
            .filter { it.key.isNotEmpty() && it.name.isNotEmpty() }
            .sortedWith(compareBy({ it.key != follow.sourceKey }, { it.name }))
        if (sites.isEmpty()) return
        _rebind.value = RebindState(follow = follow, sites = sites)
    }

    fun dismissRebind() {
        rebindToken++
        rebindWaiter?.complete(Unit)
        rebindWaiter = null
        searchCaller.cancelSearch()
        _rebind.value = null
    }

    fun rebindBackToSites() {
        rebindToken++
        searchCaller.cancelSearch()
        _rebind.value = _rebind.value?.copy(
            siteKey = null,
            siteName = null,
            searching = false,
            results = emptyList(),
        )
    }

    fun chooseRebindSite(site: RebindSite) {
        val state = _rebind.value ?: return
        val keyword = state.follow.name?.trim().orEmpty()
        if (keyword.isEmpty()) return
        val token = (++rebindToken).toString()
        _rebind.value = state.copy(
            siteKey = site.key,
            siteName = site.name,
            searching = true,
            results = emptyList(),
        )
        val done = CompletableDeferred<Unit>()
        rebindWaiter = done
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(REBIND_SEARCH_TIMEOUT_MS) {
                    searchCaller.getSearch(site.key, keyword, token)
                    done.await()
                }
            } finally {
                if (rebindWaiter === done) rebindWaiter = null
            }
            val current = _rebind.value
            if (current != null && current.searching && rebindToken.toString() == token) {
                _rebind.value = current.copy(searching = false)
            }
        }
    }

    fun confirmRebind(video: Movie.Video) {
        val state = _rebind.value ?: return
        val siteKey = state.siteKey ?: return
        val followId = state.follow.id
        val info = VodInfo.fromVideo(video).copy(sourceKey = siteKey)
        dismissRebind()
        viewModelScope.launch(Dispatchers.IO) {
            follows.rebind(followId, siteKey, info)
        }
    }

    private fun onRebindSearchResult(data: AbsXml?) {
        val data = data ?: return
        val state = _rebind.value ?: return
        if (data.searchToken != rebindToken.toString()) return
        if (data.sourceKey != state.siteKey) return
        rebindWaiter?.complete(Unit)
        rebindWaiter = null
        val keyword = state.follow.name?.trim().orEmpty()
        val videos = data.movie?.videoList.orEmpty()
            .sortedByDescending { it.name?.trim() == keyword }
        _rebind.value = state.copy(searching = false, results = videos)
    }

    override fun onCleared() {
        searchCaller.cancelSearch()
        super.onCleared()
    }

    private companion object {
        const val REBIND_SEARCH_TIMEOUT_MS = 20_000L
        const val SITE_DETAIL_TIMEOUT_MS = 15_000L
        const val SITE_CHECK_INTERVAL_MS = 60L * 60 * 1000
        const val SITE_CHECK_GAP_MS = 400L
    }
}
