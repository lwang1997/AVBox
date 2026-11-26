package com.github.avbox.osc.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.TmdbEpisode
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.data.EpisodeMatcher
import com.github.avbox.osc.ui.components.AVBoxBottomSheet
import com.github.avbox.osc.ui.components.LocalSheetDismiss
import com.github.avbox.osc.ui.theme.filterChipColors
import com.github.avbox.osc.util.TmdbImages
import kotlinx.coroutines.launch

private val EpisodeThumbWidth = 210.dp

/** 选集宫格单列最小宽度：只用来推导列数，实际卡片宽度按可用宽度均分填满。 */
private val EpisodeGridMinCardWidth = 56.dp

/** 手机宽度上限 5 列；平板 / 桌面不设上限，继续按可用宽度自适应。 */
private const val EpisodeGridCompactMaxColumns = 5
private const val EpisodeGridRowsPerPage = 3
private val EpisodeGridChipHeight = 44.dp
private val EpisodeGridSpacing = 8.dp

private const val WideEpisodeRowsPerPage = 5
private val WideEpisodeRowHeight = 48.dp

private const val EpisodeSheetHeightFraction = 0.6f

/**
 * 只有当选集区内容确实超出限高、需要内部滚动时，才拦截纵向滚动继续传给父级详情页。
 * 内容不足时放行，避免用户在选集区滑动却滚不动整个页面。
 */
@Composable
private fun rememberConfineEpisodeScroll(scrollState: ScrollState): NestedScrollConnection {
    return remember(scrollState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = if (scrollState.maxValue > 0) Offset(0f, available.y) else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (scrollState.maxValue > 0) Velocity(0f, available.y) else Velocity.Zero
        }
    }
}

@Composable
internal fun EpisodeRow(
    vm: DetailViewModel,
    info: VodInfo,
    episodes: List<VodInfo.VodSeries>,
    playIndex: Int,
    currentFlag: String?,
) {
    val seasonHint = remember(info.name, currentFlag) {
        vm.seasonHintFor(info.name, currentFlag)
    }
    val episodeNumbers = remember(episodes, info.reverseSort) {
        episodes.map { EpisodeMatcher.extractEpisodeNumber(it.name) }
    }
    val episodeLabels = remember(episodes) {
        episodes.map { EpisodeMatcher.extractEpisodeLabel(it.name) }
    }
    val episodeMeta = rememberEpisodeMeta(
        vm,
        info.name,
        info.en,
        info.year,
        episodes.size,
        seasonHint,
        episodeNumbers,
        info.reverseSort,
    )
    val hasThumbnails = !episodeMeta.isNullOrEmpty()
    val episodeListWide by vm.episodeListWide.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_episodes))
            Text(
                text = stringResource(R.string.detail_episodes),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            PillAction(
                iconRes = if (info.reverseSort) {
                    R.drawable.ic_episode_order_asc
                } else {
                    R.drawable.ic_episode_reverse
                },
                text = stringResource(if (info.reverseSort) R.string.detail_order_asc else R.string.detail_order_desc),
                onClick = { vm.toggleReverse() },
            )
            if (!hasThumbnails) {
                Spacer(Modifier.width(2.dp))
                PillAction(
                    iconRes = if (episodeListWide) {
                        R.drawable.ic_layout_horizontal
                    } else {
                        R.drawable.ic_layout_vertical
                    },
                    text = stringResource(if (episodeListWide) R.string.detail_episode_style_grid else R.string.detail_episode_style_wide),
                    onClick = { vm.toggleEpisodeListStyle() },
                )
            }
            Spacer(Modifier.width(2.dp))
            PillAction(
                iconRes = R.drawable.ic_episode_grid_all,
                text = stringResource(R.string.common_all),
                onClick = { vm.showEpisodeSheet() },
            )
        }
        if (hasThumbnails) {
            ThumbnailEpisodeRow(
                episodes = episodes,
                episodeNumbers = episodeNumbers,
                episodeMeta = episodeMeta,
                playIndex = playIndex,
                reverseSort = info.reverseSort,
                currentFlag = currentFlag,
                onClick = { vm.onEpisodeClick(it) },
            )
        } else if (episodeListWide) {
            WideEpisodeList(
                episodes = episodes,
                playIndex = playIndex,
                reverseSort = info.reverseSort,
                currentFlag = currentFlag,
                onClick = { vm.onEpisodeClick(it) },
            )
        } else {
            CompactEpisodeGrid(
                episodes = episodes,
                episodeLabels = episodeLabels,
                playIndex = playIndex,
                reverseSort = info.reverseSort,
                currentFlag = currentFlag,
                onClick = { vm.onEpisodeClick(it) },
            )
        }
    }
}

@Composable
private fun ThumbnailEpisodeRow(
    episodes: List<VodInfo.VodSeries>,
    episodeNumbers: List<Int>,
    episodeMeta: List<TmdbEpisode>,
    playIndex: Int,
    reverseSort: Boolean,
    currentFlag: String?,
    onClick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    var prevReverseSort by remember { mutableStateOf(reverseSort) }
    LaunchedEffect(playIndex, currentFlag, episodes.size, reverseSort) {
        if (episodes.isEmpty()) return@LaunchedEffect
        val reverseChanged = reverseSort != prevReverseSort
        prevReverseSort = reverseSort
        if (reverseChanged) {
            listState.scrollToItem(0)
        } else if (playIndex >= 0) {
            listState.scrollToItem(minOf(playIndex, episodes.size - 1))
        }
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(episodes) { index, ep ->
            EpisodeCard(
                name = ep.name ?: (index + 1).toString(),
                episodeNumber = episodeNumbers.getOrNull(index)?.takeIf { it > 0 } ?: (index + 1),
                episode = episodeMeta.getOrNull(index),
                selected = index == playIndex,
                onClick = { onClick(index) },
            )
        }
    }
}

@Composable
private fun CompactEpisodeGrid(
    episodes: List<VodInfo.VodSeries>,
    episodeLabels: List<String>,
    playIndex: Int,
    reverseSort: Boolean,
    currentFlag: String?,
    onClick: (Int) -> Unit,
) {
    if (episodes.isEmpty()) return
    val maxGridHeight = EpisodeGridChipHeight * EpisodeGridRowsPerPage +
        EpisodeGridSpacing * (EpisodeGridRowsPerPage - 1)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        // 横向优先填满：列数按可用宽度自适应，纵向最多 3 行。
        // 手机（窗口 < 600dp）锁 5 列，避免 411dp 这类大屏手机碎成 6 列；平板 / 桌面按宽度继续增加列数。
        val maxColumns = if (currentWindowWidthClass() == WindowWidthClass.Compact) {
            EpisodeGridCompactMaxColumns
        } else {
            Int.MAX_VALUE
        }
        val columns = ((maxWidth + EpisodeGridSpacing) /
            (EpisodeGridMinCardWidth + EpisodeGridSpacing) + 0.01f)
            .toInt()
            .coerceIn(1, maxColumns)

        val scrollState = rememberScrollState()
        val scrollConnection = rememberConfineEpisodeScroll(scrollState)
        var prevReverseSort by remember { mutableStateOf(reverseSort) }
        LaunchedEffect(playIndex, currentFlag, episodes.size, reverseSort, columns) {
            if (reverseSort != prevReverseSort) {
                prevReverseSort = reverseSort
                scrollState.scrollTo(0)
            } else if (playIndex >= 0) {
                val row = minOf(playIndex, episodes.lastIndex) / columns
                scrollState.scrollTo(((EpisodeGridChipHeight + EpisodeGridSpacing) * row).value.toInt())
            }
        }

        // 渲染全部集数，超出限高部分在区域内滚动翻页（不再只取前 15 集）。
        // 满行用 weight 等分铺满可用宽度（左右各 16dp 内边距，左右对称）；
        // 末行不满时右侧补等宽空位，卡片宽度与首行一致，并保持与首行左对齐。
        Column(
            modifier = Modifier
                .heightIn(max = maxGridHeight)
                .nestedScroll(scrollConnection)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(EpisodeGridSpacing),
        ) {
            episodes.chunked(columns).forEachIndexed { rowIndex, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(EpisodeGridSpacing),
                ) {
                    rowItems.forEachIndexed { colIndex, _ ->
                        val index = rowIndex * columns + colIndex
                        EpisodeNumberCard(
                            name = episodeLabels.getOrNull(index)?.takeIf { it.isNotEmpty() }
                                ?: (index + 1).toString(),
                            selected = index == playIndex,
                            onClick = { onClick(index) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun WideEpisodeList(
    episodes: List<VodInfo.VodSeries>,
    playIndex: Int,
    reverseSort: Boolean,
    currentFlag: String?,
    onClick: (Int) -> Unit,
) {
    if (episodes.isEmpty()) return
    val maxListHeight = WideEpisodeRowHeight * WideEpisodeRowsPerPage +
        EpisodeGridSpacing * (WideEpisodeRowsPerPage - 1)

    val scrollState = rememberScrollState()
    val scrollConnection = rememberConfineEpisodeScroll(scrollState)
    var prevReverseSort by remember { mutableStateOf(reverseSort) }
    LaunchedEffect(playIndex, currentFlag, episodes.size, reverseSort) {
        if (reverseSort != prevReverseSort) {
            prevReverseSort = reverseSort
            scrollState.scrollTo(0)
        } else if (playIndex >= 0) {
            val row = minOf(playIndex, episodes.lastIndex)
            scrollState.scrollTo(((WideEpisodeRowHeight + EpisodeGridSpacing) * row).value.toInt())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxListHeight)
            .nestedScroll(scrollConnection)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(EpisodeGridSpacing),
    ) {
        episodes.forEachIndexed { index, ep ->
            WideEpisodeRow(
                name = ep.name ?: (index + 1).toString(),
                selected = index == playIndex,
                onClick = { onClick(index) },
            )
        }
    }
}

@Composable
private fun WideEpisodeRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(WideEpisodeRowHeight)
            .detailGlass(shape)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun rememberEpisodeMeta(
    vm: DetailViewModel,
    name: String?,
    altName: String?,
    year: Int,
    episodeCount: Int,
    seasonHint: Int?,
    episodeNumbers: List<Int>,
    reversed: Boolean,
): List<TmdbEpisode>? {
    val enabled = !name.isNullOrBlank() && episodeCount > 0
    val sourceOrder = remember(episodeNumbers, reversed) {
        if (reversed) episodeNumbers.reversed() else episodeNumbers
    }
    val epoch by TmdbImages.configEpoch.collectAsState()
    LaunchedEffect(vm, name, altName, year, episodeCount, seasonHint, sourceOrder, epoch) {
        vm.ensureTmdbEpisodeMeta(name, altName, year, episodeCount, seasonHint, sourceOrder)
    }
    val meta by vm.tmdbEpisodeMeta.collectAsState()
    if (!enabled) return null
    return if (reversed) meta?.asReversed() else meta
}

@Composable
private fun EpisodeCard(
    name: String,
    episodeNumber: Int,
    episode: TmdbEpisode?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val stillPath = episode?.stillPath.orEmpty()
    val title = episode?.name?.takeIf { it.isNotBlank() } ?: name
    Column(
        modifier = Modifier
            .width(EpisodeThumbWidth)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(detailCardColor())
                .then(
                    if (selected) {
                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (stillPath.isNotEmpty()) {
                AsyncImage(
                    model = TmdbImages.stillUrl(stillPath),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = episodeNumber.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

@Composable
private fun EpisodeNumberCard(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .height(EpisodeGridChipHeight)
            .detailGlass(shape)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PillAction(iconRes: Int, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeSheet(vm: DetailViewModel, slideFromEnd: Boolean, showQuality: Boolean) {
    val vodUi by vm.vodUi.collectAsState()
    val show by vm.episodeSheet.collectAsState()
    if (!show) return
    val info = vodUi?.info ?: return
    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex
    val qualityOptions by vm.qualityOptions.collectAsState()
    val qualitySelected by vm.qualitySelected.collectAsState()

    val groupCount = when {
        episodes.size > 400 -> 120
        episodes.size > 100 -> 60
        else -> 20
    }
    val groups = if (episodes.size > groupCount) {
        val result = ArrayList<String>()
        var i = 0
        while (i < episodes.size) {
            val end = minOf(i + groupCount, episodes.size)
            result.add("${i + 1} - $end")
            i += groupCount
        }
        result
    } else {
        emptyList()
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val gridScope = rememberCoroutineScope()
    var selectedGroup by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(show, currentFlag, playIndex) {
        if (show && playIndex >= 0) {
            selectedGroup = playIndex / groupCount
            if (playIndex in episodes.indices) gridState.scrollToItem(playIndex)
        }
    }

    AVBoxBottomSheet(
        onDismissRequest = { vm.dismissEpisodeSheet() },
        title = if (info.name.isNullOrEmpty()) {
                stringResource(R.string.detail_episodes)
            } else {
                stringResource(R.string.detail_episodes_of, info.name.orEmpty())
            },
        isScrollable = false,
        slideFromEnd = slideFromEnd,
        modifier = if (slideFromEnd) {
            Modifier
        } else {
            Modifier.fillMaxHeight(EpisodeSheetHeightFraction)
        },
    ) {
        val dismissAnimated = LocalSheetDismiss.current
        Column(modifier = Modifier.fillMaxWidth()) {
            if (showQuality && qualityOptions.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(qualityOptions) { index, option ->
                        FilterChip(
                            selected = index == qualitySelected,
                            onClick = { vm.switchQuality(index) },
                            label = { Text(option) },
                            shape = RoundedCornerShape(20.dp),
                            colors = MaterialTheme.colorScheme.filterChipColors(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (groups.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(groups) { index, label ->
                        FilterChip(
                            selected = index == selectedGroup,
                            onClick = {
                                selectedGroup = index
                                gridScope.launch { gridState.scrollToItem(index * groupCount) }
                            },
                            label = { Text(label) },
                            shape = RoundedCornerShape(20.dp),
                            colors = MaterialTheme.colorScheme.filterChipColors(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            val maxNameLength = episodes.maxOfOrNull { it.name?.length ?: 0 } ?: 0
            val gridColumnCount = when {
                maxNameLength <= 4 -> if (slideFromEnd) 2 else 4
                maxNameLength <= 12 -> 2
                else -> 1
            }
            val gridModifier = Modifier
                .fillMaxWidth()
                .weight(1f)
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                state = gridState,
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(gridColumnCount),
                modifier = gridModifier,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding() + 16.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItemsIndexed(episodes) { index, ep ->
                    FilterChip(
                        selected = index == playIndex,
                        onClick = {
                            vm.onEpisodeClick(index)
                            dismissAnimated()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        label = {
                            Text(
                                text = ep.name ?: (index + 1).toString(),
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = MaterialTheme.colorScheme.filterChipColors(),
                    )
                }
            }
        }
    }
}
