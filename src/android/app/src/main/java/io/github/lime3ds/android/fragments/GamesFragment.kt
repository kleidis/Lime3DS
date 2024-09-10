// Copyright Citra Emulator Project / Lime3DS Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package io.github.lime3ds.android.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.transition.MaterialFadeThrough
import info.debatty.java.stringsimilarity.Jaccard
import info.debatty.java.stringsimilarity.JaroWinkler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.temporal.ChronoField
import java.util.Locale
import io.github.lime3ds.android.LimeApplication
import io.github.lime3ds.android.R
import io.github.lime3ds.android.adapters.GameAdapter
import io.github.lime3ds.android.databinding.FragmentGamesBinding
import io.github.lime3ds.android.features.settings.model.Settings
import io.github.lime3ds.android.model.Game
import io.github.lime3ds.android.viewmodel.GamesViewModel
import io.github.lime3ds.android.viewmodel.HomeViewModel

class GamesFragment : Fragment() {
    private var _binding: FragmentGamesBinding? = null
    private val binding get() = _binding!!

    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()

    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGamesBinding.inflate(inflater)
        return binding.root
    }

    // This is using the correct scope, lint is just acting up
    @SuppressLint("UnsafeRepeatOnLifecycleDetector")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        homeViewModel.setNavigationVisibility(visible = true, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = true)

        val inflater = LayoutInflater.from(requireContext())

        preferences = PreferenceManager.getDefaultSharedPreferences(LimeApplication.appContext)

        binding.gridGames.apply {
            layoutManager = GridLayoutManager(
                requireContext(),
                resources.getInteger(R.integer.game_grid_columns)
            )
            adapter = GameAdapter(requireActivity() as AppCompatActivity, inflater)
        }

        binding.swipeRefresh.apply {
            // Add swipe down to refresh gesture
            setOnRefreshListener {
                gamesViewModel.reloadGames(false)
            }

            // Set theme color to the refresh animation's background
            setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(
                    binding.swipeRefresh,
                    com.google.android.material.R.attr.colorPrimary
                )
            )
            setColorSchemeColors(
                MaterialColors.getColor(
                    binding.swipeRefresh,
                    com.google.android.material.R.attr.colorOnPrimary
                )
            )
            post {
                if (_binding == null) {
                    return@post
                }
                binding.swipeRefresh.isRefreshing = gamesViewModel.isReloading.value
            }
        }

        binding.chipGroup.setOnCheckedStateChangeListener { _, _ -> filterAndSearch() }

        binding.searchText.doOnTextChanged { text: CharSequence?, _: Int, _: Int, _: Int ->
            if (text.toString().isNotEmpty()) {
                binding.clearButton.visibility = View.VISIBLE
                binding.swipeRefresh.isEnabled = false
            } else {
                binding.clearButton.visibility = View.INVISIBLE
                binding.swipeRefresh.isEnabled = true
                gamesViewModel.setSearchedGames(gamesViewModel.games.value)
            }
            filterAndSearch()
        }

        viewLifecycleOwner.lifecycleScope.apply {
            launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    gamesViewModel.isReloading.collect { isReloading ->
                        binding.swipeRefresh.isRefreshing = isReloading
                        if (gamesViewModel.games.value.isEmpty() && !isReloading) {
                            binding.noticeText.visibility = View.VISIBLE
                        } else {
                            binding.noticeText.visibility = View.GONE
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    gamesViewModel.games.collectLatest { setAdapter(it) }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    gamesViewModel.shouldSwapData.collect {
                        if (it) {
                            setAdapter(gamesViewModel.games.value)
                            gamesViewModel.setShouldSwapData(false)
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    gamesViewModel.searchFocused.collect {
                        if (it) {
                            focusSearch()
                            gamesViewModel.setSearchFocused(false)
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    gamesViewModel.searchedGames.collect {
                        (binding.gridGames.adapter as GameAdapter).submitList(it)
                        if (it.isEmpty()) {
                            binding.noticeText.visibility = View.VISIBLE
                        } else {
                            binding.noticeText.visibility = View.GONE
                        }
                    }
                }
            }
        }

        binding.clearButton.setOnClickListener { binding.searchText.setText("") }

        binding.searchBackground.setOnClickListener { focusSearch() }

        setInsets()
        filterAndSearch()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setAdapter(games: List<Game>) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(LimeApplication.appContext)
        if (preferences.getBoolean(Settings.PREF_SHOW_HOME_APPS, false)) {
            (binding.gridGames.adapter as GameAdapter).submitList(games)
        } else {
            val filteredList = games.filter { !it.isSystemTitle }
            (binding.gridGames.adapter as GameAdapter).submitList(filteredList)
        }
    }

    private fun filterAndSearch() {
        val searchTerm = binding.searchText.text.toString().lowercase(Locale.getDefault())
        val baseList = gamesViewModel.games.value

        val filteredList: List<Game> = when (binding.chipGroup.checkedChipId) {
            R.id.chip_recently_played -> {
                baseList.filter {
                    val lastPlayedTime = preferences.getLong(it.keyLastPlayedTime, 0L)
                    lastPlayedTime > (System.currentTimeMillis() - ChronoField.MILLI_OF_DAY.range().maximum)
                }
            }
            R.id.chip_recently_added -> {
                baseList.filter {
                    val addedTime = preferences.getLong(it.keyAddedToLibraryTime, 0L)
                    addedTime > (System.currentTimeMillis() - ChronoField.MILLI_OF_DAY.range().maximum)
                }
            }
            R.id.chip_installed -> baseList.filter { it.isInstalled }
            else -> baseList
        }

        if (searchTerm.isEmpty()) {
            gamesViewModel.setSearchedGames(filteredList)
            return
        }

        val searchAlgorithm = if (searchTerm.length > 1) Jaccard(2) else JaroWinkler()
        val sortedList: List<Game> = filteredList.mapNotNull { game ->
            val title = game.title.lowercase(Locale.getDefault())
            val score = searchAlgorithm.similarity(searchTerm, title)
            if (score > 0.03) {
                ScoredGame(score, game)
            } else {
                null
            }
        }.sortedByDescending { it.score }.map { it.item }

        if (sortedList.isEmpty()) {
            binding.noticeText.visibility = View.VISIBLE
        } else {
            binding.noticeText.visibility = View.GONE
        }

        gamesViewModel.setSearchedGames(sortedList)
    }

    private fun focusSearch() {
        if (_binding != null) {
            binding.searchText.requestFocus()
            val imm = requireActivity()
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            imm?.showSoftInput(binding.searchText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { view: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val extraListSpacing = resources.getDimensionPixelSize(R.dimen.spacing_large)
            val spacingNavigation = resources.getDimensionPixelSize(R.dimen.spacing_navigation)
            val spacingNavigationRail =
                resources.getDimensionPixelSize(R.dimen.spacing_navigation_rail)
            val chipSpacing = resources.getDimensionPixelSize(R.dimen.spacing_chip)

            binding.gridGames.updatePadding(bottom = barInsets.bottom + spacingNavigation + extraListSpacing)
            binding.frameSearch.updatePadding(top = barInsets.top + extraListSpacing)
            binding.chipGroup.updatePadding(left = chipSpacing, right = chipSpacing + spacingNavigationRail)

            binding.swipeRefresh.setProgressViewEndTarget(
                false,
                barInsets.top + resources.getDimensionPixelSize(R.dimen.spacing_refresh_end)
            )

            val leftInsets = barInsets.left + cutoutInsets.left
            val rightInsets = barInsets.right + cutoutInsets.right
            val mlpSwipe = binding.swipeRefresh.layoutParams as MarginLayoutParams
            if (ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_LTR) {
                mlpSwipe.leftMargin = leftInsets + spacingNavigationRail
                mlpSwipe.rightMargin = rightInsets
            } else {
                mlpSwipe.leftMargin = leftInsets
                mlpSwipe.rightMargin = rightInsets + spacingNavigationRail
            }
            binding.swipeRefresh.layoutParams = mlpSwipe

            binding.noticeText.updatePadding(bottom = spacingNavigation)

            windowInsets
        }

    private inner class ScoredGame(val score: Double, val item: Game)
}
