package com.ultratv.tv.nativeapp.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultratv.tv.nativeapp.data.db.CategoryEntity
import com.ultratv.tv.nativeapp.data.db.MovieEntity
import com.ultratv.tv.nativeapp.data.prefs.HiddenCategoriesStore
import com.ultratv.tv.nativeapp.data.repo.CatalogRepository
import com.ultratv.tv.nativeapp.data.repo.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Group of movies bound to a category — one rail in the Netflix-style screen. */
data class MovieRail(val category: CategoryEntity?, val items: List<MovieEntity>)

private const val DISCOVERY_RAIL_LIMIT = 16
private const val DISCOVERY_ITEMS_PER_RAIL = 25

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MoviesViewModel @Inject constructor(
    private val providerRepo: ProviderRepository,
    private val catalog: CatalogRepository,
    private val hiddenStore: HiddenCategoriesStore,
    private val movieDao: com.ultratv.tv.nativeapp.data.db.MovieDao,
) : ViewModel() {

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Re-sync the active provider's catalog. Wired to pull-to-refresh. */
    fun refresh() {
        viewModelScope.launch {
            val pid = providers.value.firstOrNull { it.active }?.id
                ?: providers.value.firstOrNull()?.id
                ?: return@launch
            _refreshing.value = true
            try {
                val active = providerRepo.byId(pid)
                if (active?.kind == "XTREAM") providerRepo.syncXtreamLibraryOnly(pid)
                else providerRepo.syncAll(pid)
            } finally {
                _refreshing.value = false
            }
        }
    }

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val providers = providerRepo.observeProviders()
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = combine(
        providers, hiddenStore.hidden,
    ) { ps, hidden -> ps to hidden }
        .flatMapLatest { (ps, hidden) ->
            val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id ?: return@flatMapLatest flowOf(emptyList())
            catalog.categories(pid, "MOVIE").map { list ->
                list.filter { hiddenStore.keyFor("MOVIE", pid, it.remoteId) !in hidden }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Flat filtered list — used when the user picks a single category from the
     * chip filter. Stays as a simple grid in that mode.
     */
    val movies: StateFlow<List<MovieEntity>> = combine(
        providers, _selectedCategory, hiddenStore.hidden,
    ) { ps, cat, hidden -> Triple(ps, cat, hidden) }
        .flatMapLatest { (ps, cat, hidden) ->
            val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id ?: return@flatMapLatest flowOf(emptyList())
            catalog.movies(pid).map { list ->
                list.filter { m ->
                    val cid = m.categoryId
                    if (cid != null && hiddenStore.keyFor("MOVIE", pid, cid) in hidden) return@filter false
                    cat == null || m.categoryId == cat
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Netflix-style rails: a list of (category, top-N items) pairs. Items are
     * capped at 25 per rail so even a 50k-movie catalog stays fluid (lazy
     * horizontal scrolling within the rail handles the rest of the discovery).
     */
    /**
     * TV discovery should not materialize the provider's entire VOD catalog.
     * Show a bounded set of provider-ordered category rails; selecting any
     * category still opens the fully paged catalog below.
     */
    val rails: StateFlow<List<MovieRail>> = combine(
        providers, categories,
    ) { ps, cats ->
        val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id
        pid to cats
    }.flatMapLatest { (pid, cats) ->
        if (pid == null) return@flatMapLatest flowOf(emptyList())

        val visibleCats = cats
            .filter { it.providerId == pid }
            .take(DISCOVERY_RAIL_LIMIT)

        val railFlows: MutableList<Flow<MovieRail>> = visibleCats.map { cat ->
            catalog.moviesForCategoryLimited(
                pid,
                cat.remoteId,
                DISCOVERY_ITEMS_PER_RAIL,
            ).map { items -> MovieRail(cat, items) }
        }.toMutableList()

        // Preserve genuinely uncategorized titles without leaking hidden
        // categories back into an "Other" rail.
        railFlows += catalog.uncategorizedMoviesLimited(
            pid,
            DISCOVERY_ITEMS_PER_RAIL,
        ).map { items -> MovieRail(null, items) }

        combine(railFlows) { values ->
            values.filter { it.items.isNotEmpty() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val featured: StateFlow<MovieEntity?> = rails
        .map { discoveryRails ->
            discoveryRails.asSequence()
                .flatMap { it.items.asSequence() }
                .maxByOrNull { (it.year ?: 0) * 100L + (it.name.length % 100) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectCategory(remoteId: String?) { _selectedCategory.value = remoteId }

    // Paged feed used by the flat-grid mode (when a category chip is selected).
    val pagedMovies: Flow<PagingData<MovieEntity>> = combine(
        providers, _selectedCategory,
    ) { ps, cat -> ps to cat }.flatMapLatest { (ps, cat) ->
        val pid = (ps.firstOrNull { it.active } ?: ps.firstOrNull())?.id
            ?: return@flatMapLatest kotlinx.coroutines.flow.emptyFlow()
        Pager(
            config = PagingConfig(
                pageSize = 60,
                prefetchDistance = 60,
                initialLoadSize = 120,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                if (cat == null) movieDao.pagedAll(pid)
                else movieDao.pagedForCategory(pid, cat)
            },
        ).flow
    }.cachedIn(viewModelScope)
}
