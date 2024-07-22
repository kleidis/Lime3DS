// Copyright 2023 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package io.github.lime3ds.android.adapters

import android.net.Uri
import android.os.SystemClock
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lime3ds.android.HomeNavigationDirections
import io.github.lime3ds.android.LimeApplication
import io.github.lime3ds.android.R
import io.github.lime3ds.android.adapters.GameAdapter.GameViewHolder
import io.github.lime3ds.android.databinding.CardGameBinding
import io.github.lime3ds.android.databinding.CardGameLargeBinding
import io.github.lime3ds.android.features.cheats.ui.CheatsFragmentDirections
import io.github.lime3ds.android.model.Game
import io.github.lime3ds.android.utils.GameIconUtils
import io.github.lime3ds.android.viewmodel.GamesViewModel


class GameAdapter(private val activity: AppCompatActivity) :
    ListAdapter<Game, GameViewHolder>(AsyncDifferConfig.Builder(DiffCallback()).build()),
    View.OnClickListener, View.OnLongClickListener {
    private var lastClickTime = 0L
    private var useLargeLayout = false

    fun toggleView(useLarge: Boolean) {
        useLargeLayout = useLarge
        notifyDataSetChanged()
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return if (useLargeLayout) {
            val binding = CardGameLargeBinding.inflate(layoutInflater, parent, false)
            GameViewHolder.LargeGameViewHolder(binding)
        } else {
            val binding = CardGameBinding.inflate(layoutInflater, parent, false)
            GameViewHolder.SmallGameViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        holder.bind(currentList[position])
    }

    override fun getItemCount(): Int = currentList.size

    /**
     * Launches the game that was clicked on.
     *
     * @param view The card representing the game the user wants to play.
     */
    override fun onClick(view: View) {
        // Double-click prevention, using threshold of 1000 ms
        if (SystemClock.elapsedRealtime() - lastClickTime < 1000) {
            return
        }
        lastClickTime = SystemClock.elapsedRealtime()

        val holder = view.tag as GameViewHolder
        gameExists(holder)

        val preferences =
            PreferenceManager.getDefaultSharedPreferences(LimeApplication.appContext)
        preferences.edit()
            .putLong(
                holder.game.keyLastPlayedTime,
                System.currentTimeMillis()
            )
            .apply()

        val action = HomeNavigationDirections.actionGlobalEmulationActivity(holder.game)
        view.findNavController().navigate(action)
    }

    /**
     * Opens the cheats settings for the game that was clicked on.
     *
     * @param view The view representing the game the user wants to play.
     */
    override fun onLongClick(view: View): Boolean {
        val context = view.context
        val holder = view.tag as GameViewHolder
        gameExists(holder)

        if (holder.game.titleId == 0L) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.properties)
                .setMessage(R.string.properties_not_loaded)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else {
            val action = CheatsFragmentDirections.actionGlobalCheatsFragment(holder.game.titleId)
            view.findNavController().navigate(action)
        }
        return true
    }

    // Triggers a library refresh if the user clicks on stale data
    private fun gameExists(holder: GameViewHolder): Boolean {
        if (holder.game.isInstalled) {
            return true
        }

        val gameExists = DocumentFile.fromSingleUri(
            LimeApplication.appContext,
            Uri.parse(holder.game.path)
        )?.exists() == true
        return if (!gameExists) {
            Toast.makeText(
                LimeApplication.appContext,
                R.string.loader_error_file_not_found,
                Toast.LENGTH_LONG
            ).show()

            ViewModelProvider(activity)[GamesViewModel::class.java].reloadGames(true)
            false
        } else {
            true
        }
    }

    sealed class GameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(game: Game)

        class SmallGameViewHolder(val binding: CardGameBinding) : GameViewHolder(binding.root) {
            lateinit var game: Game

            init {
                binding.cardGame.tag = this
            }

            override fun bind(game: Game) {
                this.game = game

                binding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
                GameIconUtils.loadGameIcon(activity, game, binding.imageGameScreen)

                binding.textGameTitle.visibility = if (game.title.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                binding.textCompany.visibility = if (game.company.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

                binding.textGameTitle.text = game.title
                binding.textCompany.text = game.company
                binding.textFilename.visibility = View.VISIBLE
                binding.textFilename.text = game.filename
                binding.textFilename.ellipsize = TextUtils.TruncateAt.MARQUEE
                binding.textFilename.isSelected = true

                val backgroundColorId =
                    if (
                        isValidGame(game.filename.substring(game.filename.lastIndexOf(".") + 1).lowercase())
                    ) {
                        R.attr.colorSurface
                    } else {
                        R.attr.colorErrorContainer
                    }
                binding.cardContents.setBackgroundColor(
                    MaterialColors.getColor(
                        binding.cardContents,
                        backgroundColorId
                    )
                )

                binding.textGameTitle.postDelayed(
                    {
                        binding.textGameTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
                        binding.textGameTitle.isSelected = true

                        binding.textCompany.ellipsize = TextUtils.TruncateAt.MARQUEE
                        binding.textCompany.isSelected = true
                    },
                    3000
                )
            }
        }

        class LargeGameViewHolder(val binding: CardGameLargeBinding) : GameViewHolder(binding.root) {
            lateinit var game: Game

            init {
                binding.cardGame.tag = this
            }

            override fun bind(game: Game) {
                this.game = game

                binding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
                GameIconUtils.loadGameIcon(activity, game, binding.imageGameScreen)

                binding.textGameTitle.visibility = if (game.title.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                binding.textCompany.visibility = if (game.company.isEmpty()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

                binding.textGameTitle.text = game.title
                binding.textCompany.text = game.company
                binding.textFilename.visibility = View.GONE

                val backgroundColorId =
                    if (
                        isValidGame(game.filename.substring(game.filename.lastIndexOf(".") + 1).lowercase())
                    ) {
                        R.attr.colorSurface
                    } else {
                        R.attr.colorErrorContainer
                    }
                binding.cardContents.setBackgroundColor(
                    MaterialColors.getColor(
                        binding.cardContents,
                        backgroundColorId
                    )
                )

                binding.textGameTitle.postDelayed(
                    {
                        binding.textGameTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
                        binding.textGameTitle.isSelected = true

                        binding.textCompany.ellipsize = TextUtils.TruncateAt.MARQUEE
                        binding.textCompany.isSelected = true
                    },
                    3000
                )
            }
        }
    }

    private fun isValidGame(extension: String): Boolean {
        return Game.badExtensions.stream()
            .noneMatch { extension == it.lowercase() }
    }

    private class DiffCallback : DiffUtil.ItemCallback<Game>() {
        override fun areItemsTheSame(oldItem: Game, newItem: Game): Boolean {
            return oldItem.titleId == newItem.titleId
        }

        override fun areContentsTheSame(oldItem: Game, newItem: Game): Boolean {
            return oldItem == newItem
        }
    }
}