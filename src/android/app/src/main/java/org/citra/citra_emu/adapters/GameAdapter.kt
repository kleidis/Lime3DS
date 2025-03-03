// Copyright Citra Emulator Project / Lime3DS Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.adapters

import android.graphics.drawable.Icon
import android.net.Uri
import android.os.SystemClock
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import org.citra.citra_emu.HomeNavigationDirections
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.citra.citra_emu.adapters.GameAdapter.GameViewHolder
import org.citra.citra_emu.databinding.CardGameBinding
import org.citra.citra_emu.features.cheats.ui.CheatsFragmentDirections
import org.citra.citra_emu.features.settings.ui.SettingsActivity
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.GameIconUtils
import org.citra.citra_emu.viewmodel.GamesViewModel

class GameAdapter(private val activity: AppCompatActivity, private val inflater: LayoutInflater) :
    ListAdapter<Game, GameViewHolder>(AsyncDifferConfig.Builder(DiffCallback()).build()),
    View.OnClickListener, View.OnLongClickListener {
    private var lastClickTime = 0L

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        // Create a new view.
        val binding = CardGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.cardGame.setOnClickListener(this)
        binding.cardGame.setOnLongClickListener(this)

        // Use that view to create a ViewHolder.
        return GameViewHolder(binding)
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
            PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        preferences.edit()
            .putLong(
                holder.game.keyLastPlayedTime,
                System.currentTimeMillis()
            )
            .apply()

        val action = HomeNavigationDirections.actionGlobalEmulationActivity(game = holder.game, shouldApplyCustomSettings = false)
        view.findNavController().navigate(action)
    }

    /**
     * Opens the about game dialog for the game that was clicked on.
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
            showAboutGameDialog(context, holder.game, holder, view)
        }
        return true
    }

    // Triggers a library refresh if the user clicks on stale data
    private fun gameExists(holder: GameViewHolder): Boolean {
        if (holder.game.isInstalled) {
            return true
        }

        val gameExists = DocumentFile.fromSingleUri(
            CitraApplication.appContext,
            Uri.parse(holder.game.path)
        )?.exists() == true
        return if (!gameExists) {
            Toast.makeText(
                CitraApplication.appContext,
                R.string.loader_error_file_not_found,
                Toast.LENGTH_LONG
            ).show()

            ViewModelProvider(activity)[GamesViewModel::class.java].reloadGames(true)
            false
        } else {
            true
        }
    }

    inner class GameViewHolder(val binding: CardGameBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var game: Game

        init {
            binding.cardGame.tag = this
        }

        fun bind(game: Game) {
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
            binding.textGameRegion.text = game.regions

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

                    binding.textGameRegion.ellipsize = TextUtils.TruncateAt.MARQUEE
                    binding.textGameRegion.isSelected = true
                },
                3000
            )
        }
    }

    private fun showAboutGameDialog(context: Context, game: Game, holder: GameViewHolder, view: View) {
        val bottomSheetView = inflater.inflate(R.layout.dialog_about_game, null)

        val bottomSheetDialog = BottomSheetDialog(context)
        bottomSheetDialog.setContentView(bottomSheetView)

        bottomSheetView.findViewById<TextView>(R.id.about_game_title).text = game.title
        bottomSheetView.findViewById<TextView>(R.id.about_game_company).text = game.company
        bottomSheetView.findViewById<TextView>(R.id.about_game_region).text = game.regions
        bottomSheetView.findViewById<TextView>(R.id.about_game_id).text = "ID: " + String.format("%016X", game.titleId)
        bottomSheetView.findViewById<TextView>(R.id.about_game_filename).text = "File: " + game.filename
        GameIconUtils.loadGameIcon(activity, game, bottomSheetView.findViewById(R.id.game_icon))

        bottomSheetView.findViewById<MaterialButton>(R.id.about_game_play).setOnClickListener {
            val items = arrayOf("Global", "Custom")
            var checkedItem = 0
            var selectedItem: String? = items[0]

            MaterialAlertDialogBuilder(context)
                .setTitle("Launch Config")
                .setSingleChoiceItems(items, checkedItem) { dialog, which ->
                    selectedItem = items[which]
                }
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    if (selectedItem == "Global") {
                        val action = HomeNavigationDirections.actionGlobalEmulationActivity(game = game, shouldApplyCustomSettings = false)
                        view.findNavController().navigate(action)
                    } else {
                        val action = HomeNavigationDirections.actionGlobalEmulationActivity(game = game, shouldApplyCustomSettings = true)
                        view.findNavController().navigate(action)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        bottomSheetView.findViewById<MaterialButton>(R.id.game_settings).setOnClickListener {
            SettingsActivity.launch(context, SettingsFile.FILE_NAME_CONFIG, String.format("%016X", game.titleId))
        }

        bottomSheetView.findViewById<MaterialButton>(R.id.game_shortcut).setOnClickListener {
            val shortcutManager = activity.getSystemService(ShortcutManager::class.java)

            CoroutineScope(Dispatchers.IO).launch {
                val bitmap = (bottomSheetView.findViewById<ImageView>(R.id.game_icon).drawable as BitmapDrawable).bitmap
                val icon = Icon.createWithBitmap(bitmap)

                val shortcut = ShortcutInfo.Builder(context, game.title)
                    .setShortLabel(game.title)
                    .setIcon(icon)
                    .setIntent(game.launchIntent.apply {
                        putExtra("launched_from_shortcut", true)
                    })
                    .build()
                shortcutManager.requestPinShortcut(shortcut, null)
            }
        }

        bottomSheetView.findViewById<MaterialButton>(R.id.cheats).setOnClickListener {
            val action = CheatsFragmentDirections.actionGlobalCheatsFragment(holder.game.titleId)
            view.findNavController().navigate(action)
            bottomSheetDialog.dismiss()
        }

        val bottomSheetBehavior = bottomSheetDialog.getBehavior()
        bottomSheetBehavior.skipCollapsed = true
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED

        bottomSheetDialog.show()
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
