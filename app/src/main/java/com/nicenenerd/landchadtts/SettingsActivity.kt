package com.nicenenerd.landchadtts

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nicenenerd.landchadtts.databinding.ActivitySettingsBinding
import com.nicenenerd.landchadtts.databinding.DialogAddVoiceBinding
import com.nicenenerd.landchadtts.databinding.ItemVoiceBinding
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var voiceAdapter: VoiceAdapter
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Populate saved settings
        binding.editEndpoint.setText(Prefs.getEndpoint(this))
        binding.editModel.setText(Prefs.getModel(this))
        binding.editApiKey.setText(Prefs.getApiKey(this))

        // Set up voice list
        voiceAdapter = VoiceAdapter(
            voices = Prefs.getVoices(this).toMutableList(),
            onDelete = { voice -> removeVoice(voice) }
        )
        binding.recyclerVoices.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = voiceAdapter
        }

        binding.buttonSave.setOnClickListener { saveSettings() }
        binding.buttonFetchVoices.setOnClickListener { fetchVoicesFromServer() }
        binding.buttonAddVoice.setOnClickListener { showAddVoiceDialog(null) }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    // ── Settings persistence ─────────────────────────────────────────────────

    private fun saveSettings() {
        val endpoint = binding.editEndpoint.text?.toString()?.trim() ?: ""
        if (endpoint.isBlank()) {
            binding.layoutEndpoint.error = getString(R.string.error_endpoint_required)
            return
        }
        binding.layoutEndpoint.error = null

        Prefs.setEndpoint(this, endpoint)
        Prefs.setModel(this, binding.editModel.text?.toString()?.trim() ?: "")
        Prefs.setApiKey(this, binding.editApiKey.text?.toString()?.trim() ?: "")

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    // ── Voice management ─────────────────────────────────────────────────────

    private fun removeVoice(voice: VoiceConfig) {
        val voices = voiceAdapter.getVoices().toMutableList()
        voices.removeAll { it.id == voice.id }
        voiceAdapter.setVoices(voices)
        Prefs.setVoices(this, voices)
        notifyVoiceDataChanged()
    }

    private fun addOrUpdateVoice(voice: VoiceConfig) {
        val voices = voiceAdapter.getVoices().toMutableList()
        val existingIndex = voices.indexOfFirst { it.id == voice.id }
        if (existingIndex >= 0) {
            voices[existingIndex] = voice
        } else {
            voices.add(voice)
        }
        voiceAdapter.setVoices(voices)
        Prefs.setVoices(this, voices)
        notifyVoiceDataChanged()
    }

    // ── Add voice dialog ─────────────────────────────────────────────────────

    private fun showAddVoiceDialog(existing: VoiceConfig?) {
        val dialogBinding = DialogAddVoiceBinding.inflate(layoutInflater)
        existing?.let {
            dialogBinding.editDisplayName.setText(it.displayName)
            dialogBinding.editVoiceId.setText(it.voiceId)
            dialogBinding.editLocale.setText(it.locale)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.add_voice else R.string.edit_voice)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dialogBinding.editDisplayName.text?.toString()?.trim() ?: ""
                val voiceId = dialogBinding.editVoiceId.text?.toString()?.trim() ?: ""
                val locale = dialogBinding.editLocale.text?.toString()?.trim() ?: ""

                if (name.isBlank() || voiceId.isBlank() || locale.isBlank()) {
                    Toast.makeText(this, R.string.error_all_fields_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                addOrUpdateVoice(
                    VoiceConfig(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        displayName = name,
                        voiceId = voiceId,
                        locale = locale
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Fetch voices from server ─────────────────────────────────────────────

    private fun fetchVoicesFromServer() {
        val endpoint = binding.editEndpoint.text?.toString()?.trim() ?: ""
        if (endpoint.isBlank()) {
            binding.layoutEndpoint.error = getString(R.string.error_endpoint_required)
            return
        }
        binding.layoutEndpoint.error = null

        val model = binding.editModel.text?.toString()?.trim() ?: ""
        val apiKey = binding.editApiKey.text?.toString()?.trim() ?: ""

        binding.buttonFetchVoices.isEnabled = false
        binding.progressFetch.visibility = View.VISIBLE

        executor.execute {
            val result = runCatching {
                TtsClient(endpoint, model, apiKey).fetchVoices()
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.buttonFetchVoices.isEnabled = true
                binding.progressFetch.visibility = View.GONE

                result.fold(
                    onSuccess = { voices ->
                        if (voices.isEmpty()) {
                            Toast.makeText(
                                this,
                                R.string.no_voices_found,
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            showFetchedVoicesDialog(voices)
                        }
                    },
                    onFailure = { e ->
                        Toast.makeText(
                            this,
                            getString(R.string.fetch_error, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    private fun showFetchedVoicesDialog(fetched: List<VoiceConfig>) {
        val names = fetched.map { "${it.displayName} (${it.locale})" }.toTypedArray()
        val checked = BooleanArray(fetched.size) { false }

        AlertDialog.Builder(this)
            .setTitle(R.string.select_voices_to_add)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.add_selected) { _, _ ->
                fetched.forEachIndexed { i, voice ->
                    if (checked[i]) addOrUpdateVoice(voice)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun notifyVoiceDataChanged() {
        sendBroadcast(
            android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED).apply {
                putExtra(android.speech.tts.TextToSpeech.Engine.EXTRA_TTS_DATA_INSTALLED, true)
            }
        )
    }

    // ── RecyclerView Adapter ─────────────────────────────────────────────────

    inner class VoiceAdapter(
        private var voices: MutableList<VoiceConfig>,
        private val onDelete: (VoiceConfig) -> Unit
    ) : RecyclerView.Adapter<VoiceAdapter.VoiceViewHolder>() {

        inner class VoiceViewHolder(val binding: ItemVoiceBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceViewHolder {
            val b = ItemVoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VoiceViewHolder(b)
        }

        override fun onBindViewHolder(holder: VoiceViewHolder, position: Int) {
            val voice = voices[position]
            holder.binding.textVoiceName.text = voice.displayName
            holder.binding.textVoiceDetails.text =
                getString(R.string.voice_details, voice.voiceId, voice.locale)
            holder.binding.buttonDeleteVoice.setOnClickListener { onDelete(voice) }
            holder.binding.root.setOnClickListener { showAddVoiceDialog(voice) }
        }

        override fun getItemCount(): Int = voices.size

        fun getVoices(): List<VoiceConfig> = voices.toList()

        fun setVoices(newVoices: List<VoiceConfig>) {
            voices.clear()
            voices.addAll(newVoices)
            notifyDataSetChanged()
        }
    }
}
