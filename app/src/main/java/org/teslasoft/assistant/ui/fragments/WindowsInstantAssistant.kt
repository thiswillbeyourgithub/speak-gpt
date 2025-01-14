/**************************************************************************
 * Copyright (c) 2023-2024 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast

import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

import com.aallam.openai.api.audio.SpeechRequest
import com.aallam.openai.api.audio.TranscriptionRequest
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionMode
import com.aallam.openai.api.chat.Parameters
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.completion.CompletionRequest
import com.aallam.openai.api.completion.TextCompletion
import com.aallam.openai.api.file.FileSource
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.image.ImageCreation
import com.aallam.openai.api.image.ImageSize
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy

import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.gson.Gson
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

import okio.FileSystem
import okio.Path.Companion.toPath

import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.adapters.AssistantAdapter
import org.teslasoft.assistant.ui.onboarding.WelcomeActivity
import org.teslasoft.assistant.ui.activities.SettingsActivity
import org.teslasoft.assistant.ui.fragments.dialogs.ActionSelectorDialog
import org.teslasoft.assistant.ui.permission.MicrophonePermissionActivity
import org.teslasoft.assistant.ui.fragments.dialogs.AddChatDialogFragment
import org.teslasoft.assistant.util.DefaultPromptsParser
import org.teslasoft.assistant.util.Hash
import org.teslasoft.assistant.util.LocaleParser

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.Base64
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class WindowsInstantAssistant : Fragment() {

    // Init UI
    private var btnAssistantVoice: ImageButton? = null
    private var btnAssistantSettings: ImageButton? = null
    private var btnAssistantShowKeyboard: ImageButton? = null
    private var btnAssistantHideKeyboard: ImageButton? = null
    private var btnSaveToChat: MaterialButton? = null
    private var btnAssistantSend: ImageButton? = null
    private var assistantMessage: EditText? = null
    private var assistantInputLayout: LinearLayout? = null
    private var assistantActionsLayout: LinearLayout? = null
    private var assistantConversation: ListView? = null
    private var assistantLoading: ProgressBar? = null

    // Init chat
    private var messages: ArrayList<HashMap<String, Any>> = arrayListOf()
    private var adapter: AssistantAdapter? = null
    private var chatMessages: ArrayList<ChatMessage> = arrayListOf()
    private lateinit var languageIdentifier: LanguageIdentifier
    private var FORCE_SLASH_COMMANDS_ENABLED: Boolean = false

    // Init states
    private var isRecording = false
    private var keyboardMode = false
    private var isTTSInitialized = false
    private var silenceMode = false
    private var chatID = ""
    private var autoLangDetect = false
    private var cancelState = false
    private var disableAutoScroll = false
    private var isProcessing = false

    // init AI
    private var ai: OpenAI? = null
    private var key: String? = null
    private var model = ""
    private var endSeparator = ""
    private var prefix = ""

    // Init DALL-e
    private var resolution = "512x152"

    // Media player for OpenAI TTS
    private var mediaPlayer: MediaPlayer? = null

    // Init preferences
    private var preferences: Preferences? = null

    // Init chat save feature
    private var chatListUpdatedListener: AddChatDialogFragment.StateChangesListener = object : AddChatDialogFragment.StateChangesListener {
        override fun onAdd(name: String, id: String, fromFile: Boolean) {
            save(id)
        }

        override fun onEdit(name: String, id: String) {
            save(id)
        }

        override fun onError(fromFile: Boolean) {
            Toast.makeText(requireActivity(), "Please fill name field", Toast.LENGTH_SHORT).show()

            val chatDialogFragment: AddChatDialogFragment = AddChatDialogFragment.newInstance("", false, true, true)
            chatDialogFragment.setStateChangedListener(this)
            chatDialogFragment.show(parentFragmentManager.beginTransaction(), "AddChatDialog")
        }

        override fun onCanceled() {
            /* unused */
        }

        override fun onDelete() {
            /* unused */
        }

        override fun onDuplicate() {
            Toast.makeText(requireActivity(), "Name must be unique", Toast.LENGTH_SHORT).show()

            val chatDialogFragment: AddChatDialogFragment = AddChatDialogFragment.newInstance("", false, true, true)
            chatDialogFragment.setStateChangedListener(this)
            chatDialogFragment.show(parentFragmentManager.beginTransaction(), "AddChatDialog")
        }
    }

    // Init audio
    private var recognizer: SpeechRecognizer? = null
    private var recorder: MediaRecorder? = null

    private var stateListener: ActionSelectorDialog.StateChangesListener = ActionSelectorDialog.StateChangesListener { type, text ->
        run {

            when (type) {
                "prompt" -> run(prefix + text + endSeparator)
                "explain" -> {
                    val parser = DefaultPromptsParser()
                    parser.init()
                    parser.addOnCompletedListener { t -> run(t) }
                    parser.parse("explanationPrompt", text, requireActivity())
                }
                "summarize" -> {
                    val parser = DefaultPromptsParser()
                    parser.init()
                    parser.addOnCompletedListener { t -> run(t) }
                    parser.parse("summarizationPrompt", text, requireActivity())
                }
                "image" -> run("/imagine $text")
                "cancel" -> requireActivity().finish()
                else -> requireActivity().finish()
            }
        }
    }

    private val speechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { /* unused */ }
        override fun onBeginningOfSpeech() { /* unused */ }
        override fun onRmsChanged(rmsdB: Float) { /* unused */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* unused */ }
        override fun onPartialResults(partialResults: Bundle?) { /* unused */ }
        override fun onEvent(eventType: Int, params: Bundle?) { /* unused */ }

        override fun onEndOfSpeech() {
            isRecording = false
            btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
        }

        override fun onError(error: Int) {
            isRecording = false
            btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
        }

        override fun onResults(results: Bundle?) {
            if (cancelState) {
                cancelState = false

                btnAssistantVoice?.isEnabled = true
                btnAssistantSend?.isEnabled = true
                assistantLoading?.visibility = View.GONE
                isRecording = false
                btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
                showKeyboard()
            } else {
                isRecording = false
                btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]

                    chatMessages.add(
                        ChatMessage(
                            role = ChatRole.User,
                            content = prefix + recognizedText + endSeparator
                        )
                    )

                    saveSettings()

                    putMessage(prefix + recognizedText + endSeparator, false)

                    hideKeyboard()
                    btnAssistantVoice?.isEnabled = false
                    btnAssistantSend?.isEnabled = false
                    assistantLoading?.visibility = View.VISIBLE

                    CoroutineScope(Dispatchers.Main).launch {
                        generateResponse(prefix + recognizedText + endSeparator, true)
                    }
                }
            }
        }
    }

    // Init TTS
    private var tts: TextToSpeech? = null
    private val ttsListener: TextToSpeech.OnInitListener =
        TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsPostInit()
            }
        }

    private fun ttsPostInit() {
        if (!autoLangDetect) {
            val result = tts!!.setLanguage(LocaleParser.parse(preferences!!.getLanguage()))

            isTTSInitialized = !(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)

            val voices: Set<Voice> = tts!!.voices
            for (v: Voice in voices) {
                if (v.name == preferences!!.getVoice()) {
                    tts!!.voice = v
                }
            }
        }
    }

    // Init permissions screen
    private val permissionResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        run {
            if (result.resultCode == Activity.RESULT_OK) {
                startRecognition()
            }
        }
    }

    private val permissionResultLauncherV2 = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        run {
            if (result.resultCode == Activity.RESULT_OK) {
                startWhisper()
            }
        }
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        loadModel()
        loadResolution()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_windows_instant_assistant, container, false)
    }

    override fun onDestroy() {
        super.onDestroy()

        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }

        if (mediaPlayer!!.isPlaying) {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Suppress("unchecked")
    private fun initSettings() {
        key = preferences!!.getApiKey(requireActivity())

        endSeparator = preferences!!.getEndSeparator()
        prefix = preferences!!.getPrefix()

        loadResolution()

        if (key == null) {
            startActivity(Intent(requireActivity(), WelcomeActivity::class.java).setAction(Intent.ACTION_VIEW))
            requireActivity().finishAndRemoveTask()
        } else {
            silenceMode = preferences!!.getSilence()
            autoLangDetect = preferences!!.getAutoLangDetect()

            messages = ArrayList()

            adapter = AssistantAdapter(messages, requireActivity(), preferences!!)

            assistantConversation?.adapter = adapter
            assistantConversation?.dividerHeight = 0

            adapter?.notifyDataSetChanged()

            assistantConversation?.setOnTouchListener { _, event -> run {
                if (event.action == MotionEvent.ACTION_SCROLL || event.action == MotionEvent.ACTION_UP) {
                    assistantConversation?.transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
                    disableAutoScroll = true
                }
                return@setOnTouchListener false
            } }

            initSpeechListener()
            initTTS()
            initLogic()
            initAI()
        }
    }

    private fun initLogic() {
        btnAssistantVoice?.setOnClickListener {
            if (preferences!!.getAudioModel() == "google") {
                handleGoogleSpeechRecognition()
            } else {
                handleWhisperSpeechRecognition()
            }
        }

        btnAssistantVoice?.setOnLongClickListener {
            if (isRecording) {
                cancelState = true
                try {
                    if (mediaPlayer!!.isPlaying) {
                        mediaPlayer!!.stop()
                        mediaPlayer!!.reset()
                    }
                    tts!!.stop()
                } catch (_: java.lang.Exception) {/**/}
                btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
                if (preferences!!.getAudioModel() == "google") recognizer?.stopListening()
                isRecording = false
            }

            return@setOnLongClickListener true
        }

        btnAssistantSend?.setOnClickListener {
            parseMessage(assistantMessage?.text.toString())
        }

        btnAssistantSettings?.setOnClickListener {
            val i = Intent(
                requireActivity(),
                SettingsActivity::class.java
            ).setAction(Intent.ACTION_VIEW)

            settingsLauncher.launch(
                i
            )
        }
    }

    private fun startWhisper() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            recorder = MediaRecorder(requireActivity()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile("${requireActivity().externalCacheDir?.absolutePath}/tmp.m4a")

                if (!cancelState) {
                    try {
                        prepare()
                    } catch (e: IOException) {
                        btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
                        isRecording = false
                        MaterialAlertDialogBuilder(
                            requireActivity(),
                            R.style.App_MaterialAlertDialog
                        )
                            .setTitle("Audio error")
                            .setMessage("Failed to initialize microphone")
                            .setPositiveButton("Close") { _, _, -> }
                            .show()
                    }

                    start()
                } else {
                    cancelState = false
                    btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
                    isRecording = false
                }
            }
        } else {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile("${requireActivity().externalCacheDir?.absolutePath}/tmp.m4a")

                if (!cancelState) {
                    try {
                        prepare()
                    } catch (e: IOException) {
                        btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
                        isRecording = false
                        MaterialAlertDialogBuilder(requireActivity(), R.style.App_MaterialAlertDialog)
                            .setTitle("Audio error")
                            .setMessage("Failed to initialize microphone")
                            .setPositiveButton("Close") { _, _, -> }
                            .show()
                    }

                    start()
                } else {
                    cancelState = false
                    btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
                    isRecording = false
                }
            }
        }
    }

    private fun stopWhisper() {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null

        btnAssistantVoice?.isEnabled = false
        btnAssistantSend?.isEnabled = false
        assistantLoading?.visibility = View.VISIBLE

        if (!cancelState) {
            CoroutineScope(Dispatchers.Main).launch {
                processRecording()
            }
        } else {
            cancelState = false
            btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
            isRecording = false
        }
    }

    private suspend fun processRecording() {
        try {
            val transcriptionRequest = TranscriptionRequest(
                audio = FileSource(
                    path = "${requireActivity().externalCacheDir?.absolutePath}/tmp.m4a".toPath(),
                    fileSystem = FileSystem.SYSTEM
                ),
                model = ModelId("whisper-1"),
            )
            val transcription = ai?.transcription(transcriptionRequest)!!.text

            if (transcription.trim() == "") {
                isRecording = false
                btnAssistantVoice?.isEnabled = true
                btnAssistantSend?.isEnabled = true
                assistantLoading?.visibility = View.GONE
                btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
            } else {
                putMessage(prefix + transcription + endSeparator, false)

                chatMessages.add(
                    ChatMessage(
                        role = ChatRole.User,
                        content = prefix + transcription + endSeparator
                    )
                )

                saveSettings()

                btnAssistantVoice?.isEnabled = false
                btnAssistantSend?.isEnabled = false
                assistantLoading?.visibility = View.VISIBLE

                CoroutineScope(Dispatchers.Main).launch {
                    generateResponse(prefix + transcription + endSeparator, true)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(requireActivity(), "Failed to record audio", Toast.LENGTH_SHORT).show()
            btnAssistantVoice?.isEnabled = true
            btnAssistantSend?.isEnabled = true
            assistantLoading?.visibility = View.GONE
        }
    }

    private fun handleWhisperSpeechRecognition() {
        if (isRecording) {
            btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
            isRecording = false
            stopWhisper()
        } else {
            btnAssistantVoice?.setImageResource(R.drawable.ic_stop_recording_v2)
            isRecording = true

            if (ContextCompat.checkSelfPermission(
                    requireActivity(), Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startWhisper()
            } else {
                permissionResultLauncherV2.launch(
                    Intent(
                        requireActivity(),
                        MicrophonePermissionActivity::class.java
                    ).setAction(Intent.ACTION_VIEW)
                )
            }
        }
    }

    private fun handleGoogleSpeechRecognition() {
        if (isRecording) {
            try {
                if (mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.stop()
                    mediaPlayer!!.reset()
                }
                tts!!.stop()
            } catch (_: java.lang.Exception) {/**/}
            btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
            recognizer?.stopListening()
            isRecording = false
        } else {
            try {
                if (mediaPlayer!!.isPlaying) {
                    mediaPlayer!!.stop()
                    mediaPlayer!!.reset()
                }
                tts!!.stop()
            } catch (_: java.lang.Exception) {/**/}
            btnAssistantVoice?.setImageResource(R.drawable.ic_stop_recording_v2)
            if (ContextCompat.checkSelfPermission(
                    requireActivity(), Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startRecognition()
            } else {
                permissionResultLauncher.launch(
                    Intent(
                        requireActivity(),
                        MicrophonePermissionActivity::class.java
                    ).setAction(Intent.ACTION_VIEW)
                )
            }

            isRecording = true
        }
    }

    private fun initSpeechListener() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(requireActivity())
        recognizer?.setRecognitionListener(speechListener)
    }

    private fun initTTS() {
        tts = TextToSpeech(requireActivity(), ttsListener)
    }

    private fun initAI() {
        if (key == null) {
            startActivity(Intent(requireActivity(), WelcomeActivity::class.java).setAction(Intent.ACTION_VIEW))
            requireActivity().finish()
        } else {
            val config = OpenAIConfig(
                token = key!!,
                logging = LoggingConfig(LogLevel.None, Logger.Simple),
                timeout = Timeout(socket = 30.seconds),
                organization = null,
                headers = emptyMap(),
                host = OpenAIHost(preferences!!.getCustomHost()),
                proxy = null,
                retry = RetryStrategy()
            )

            ai = OpenAI(config)
            loadModel()
            setup()
        }
    }

    private fun setup() {
        endSeparator = preferences!!.getEndSeparator()
        prefix = preferences!!.getPrefix()
        val extras: Bundle? = requireActivity().intent.extras

        if (extras != null) {
            val tryPrompt: String = extras.getString("prompt", "")
            val runWithParams: String = extras.getString("runWithParams", "false")

            FORCE_SLASH_COMMANDS_ENABLED = extras.getBoolean("FORCE_SLASH_COMMANDS_ENABLED", false)

            if (tryPrompt != "") {
                if (runWithParams == "true") {
                    CoroutineScope(Dispatchers.Main).launch {
                        val actionSelectorDialog: ActionSelectorDialog =
                            ActionSelectorDialog.newInstance(tryPrompt)
                        actionSelectorDialog.setStateChangedListener(stateListener)
                        actionSelectorDialog.show(
                            parentFragmentManager.beginTransaction(),
                            "ActionSelectorDialog\$setup()"
                        )
                    }
                } else {
                    run(prefix + tryPrompt + endSeparator)
                }
            } else {
                runFromShareIntent()
            }
        } else {
            runFromShareIntent()
        }
    }

    private fun runFromShareIntent() {
        if (requireActivity().intent?.action == Intent.ACTION_SEND && requireActivity().intent.type == "text/plain") {
            val receivedText = requireActivity().intent.getStringExtra(Intent.EXTRA_TEXT)
            if (receivedText != null) {
                CoroutineScope(Dispatchers.Main).launch {
                    val actionSelectorDialog: ActionSelectorDialog =
                        ActionSelectorDialog.newInstance(receivedText)
                    actionSelectorDialog.setStateChangedListener(stateListener)
                    actionSelectorDialog.show(
                        parentFragmentManager.beginTransaction(),
                        "ActionSelectorDialog\$runFromShareIntent()"
                    )
                }
            } else {
                runFromContextMenu()
            }
        } else {
            runFromContextMenu()
        }
    }

    private fun runFromContextMenu() {
        val tryPrompt = requireActivity().intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString()

        if (tryPrompt != "" && tryPrompt != "null") {
            CoroutineScope(Dispatchers.Main).launch {
                val actionSelectorDialog: ActionSelectorDialog =
                    ActionSelectorDialog.newInstance(tryPrompt)
                actionSelectorDialog.setStateChangedListener(stateListener)
                actionSelectorDialog.show(
                    parentFragmentManager.beginTransaction(),
                    "ActionSelectorDialog\$runFromContextMenu()"
                )
            }
        } else {
            runActivationPrompt()
        }
    }

    private fun runActivationPrompt() {
        if (messages.isEmpty()) {
            val prompt: String = preferences!!.getPrompt()

            if (prompt.toString() != "" && prompt.toString() != "null" && prompt != "") {
                putMessage(prompt, false)

                chatMessages.add(
                    ChatMessage(
                        role = ChatRole.User,
                        content = prompt
                    )
                )

                hideKeyboard()
                btnAssistantVoice?.isEnabled = false
                btnAssistantSend?.isEnabled = false
                assistantLoading?.visibility = View.VISIBLE

                CoroutineScope(Dispatchers.Main).launch {
                    generateResponse(prompt, false)
                }
            }
        }
    }

    private fun parseMessage(message: String) {
        try {
            if (mediaPlayer!!.isPlaying) {
                mediaPlayer!!.stop()
                mediaPlayer!!.reset()
            }
            tts!!.stop()
        } catch (_: java.lang.Exception) {/**/}
        if (message != "") {
            assistantMessage?.setText("")

            keyboardMode = false

            val m = prefix + message + endSeparator

            saveSettings()

            putMessage(m, false)

            hideKeyboard()
            btnAssistantVoice?.isEnabled = false
            btnAssistantSend?.isEnabled = false
            assistantLoading?.visibility = View.VISIBLE

            val imagineCommandEnabled: Boolean = preferences!!.getImagineCommand()

            if (m.lowercase().contains("/imagine") && m.length > 9 && (imagineCommandEnabled || FORCE_SLASH_COMMANDS_ENABLED)) {
                val x: String = m.substring(9)

                sendImageRequest(x)
            } else if (m.lowercase().contains("/imagine") && m.length <= 9 && imagineCommandEnabled) {
                putMessage("Prompt can not be empty. Use /imagine &lt;PROMPT&gt;", true)

                saveSettings()

                btnAssistantVoice?.isEnabled = true
                btnAssistantSend?.isEnabled = true
                assistantLoading?.visibility = View.GONE
                showKeyboard()
            } else {
                chatMessages.add(
                    ChatMessage(
                        role = ChatRole.User,
                        content = m
                    )
                )

                CoroutineScope(Dispatchers.Main).launch {
                    generateResponse(m, false)
                }
            }
        }
    }

    private fun loadModel() {
        model = preferences!!.getModel()
        endSeparator = preferences!!.getEndSeparator()
        prefix = preferences!!.getPrefix()
    }

    private fun loadResolution() {
        resolution = preferences!!.getResolution()
    }

    private fun sendImageRequest(str: String) {
        CoroutineScope(Dispatchers.Main).launch {
            generateImage(str)
        }
    }

    private fun startRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LocaleParser.parse(preferences!!.getLanguage()))
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

        recognizer?.startListening(intent)
    }

    private fun putMessage(message: String, isBot: Boolean) {
        val map: HashMap<String, Any> = HashMap()

        map["message"] = message
        map["isBot"] = isBot

        messages.add(map)
        adapter?.notifyDataSetChanged()

        assistantConversation?.post {
            assistantConversation?.setSelection(adapter?.count!! - 1)
        }
    }

    private fun generateImages(prompt: String) {
        sendImageRequest(prompt)
    }

    private fun searchInternet(prompt: String) {
        putMessage("Searching at Google...", true)

        saveSettings()

        btnAssistantVoice?.isEnabled = true
        btnAssistantSend?.isEnabled = true
        assistantLoading?.visibility = View.GONE
        showKeyboard()

        val q = prompt.replace(" ", "+")

        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.data = Uri.parse("https://www.google.com/search?q=$q")
        startActivity(intent)
    }

    private suspend fun generateResponse(request: String, shouldPronounce: Boolean) {
        isProcessing = true
        assistantConversation?.visibility = View.VISIBLE
        btnSaveToChat?.visibility = View.VISIBLE

        disableAutoScroll = false
        assistantConversation?.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL

        try {
            var response = ""
            if (!model.contains("gpt") || model.contains(":ft-")) {
                putMessage("", true)

                val completionRequest = CompletionRequest(
                    model = ModelId(model),
                    prompt = request,
                    echo = false
                )

                val completions: Flow<TextCompletion> = ai!!.completions(completionRequest)

                completions.collect { v ->
                    run {
                        if (v.choices[0].text != "null") {
                            response += v.choices[0].text
                            messages[messages.size - 1]["message"] = "$response █"
                            adapter?.notifyDataSetChanged()
                        }
                    }
                }

                chatMessages.add(ChatMessage(
                    role = ChatRole.Assistant,
                    content = response
                ))

                pronounce(shouldPronounce, response)

                saveSettings()

                btnAssistantVoice?.isEnabled = true
                btnAssistantSend?.isEnabled = true
                assistantLoading?.visibility = View.GONE
                showKeyboard()
                isProcessing = false
            } else {
                val functionCallingEnabled: Boolean = preferences!!.getFunctionCalling()

                if (functionCallingEnabled) {
                    val imageParams = Parameters.buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("prompt") {
                                put("type", "string")
                                put("description", "The prompt for image generation")
                            }
                        }
                        putJsonArray("required") {
                            add("prompt")
                        }
                    }

                    val searchParams = Parameters.buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("prompt") {
                                put("type", "string")
                                put("description", "Search query")
                            }
                        }
                        putJsonArray("required") {
                            add("prompt")
                        }
                    }

                    val cm = mutableListOf(
                        ChatMessage(
                            role = ChatRole.User,
                            content = request
                        )
                    )

                    val functionRequest = chatCompletionRequest {
                        model = ModelId(this@WindowsInstantAssistant.model)
                        messages = cm
                        functions {
                            function {
                                name = "generateImages"
                                description = "Generate an image based on the entered prompt"
                                parameters = imageParams
                            }

                            function {
                                name = "searchInternet"
                                description = "Search the Internet"
                                parameters = searchParams
                            }
                        }
                        functionCall = FunctionMode.Auto
                    }

                    val response1 = ai?.chatCompletion(functionRequest)

                    val message = response1?.choices?.first()?.message

                    if (message?.functionCall != null) {
                        val functionCall = message.functionCall!!
                        val imageGenerationAvailable = mapOf("generateImages" to ::generateImages)
                        val searchInternetAvailable = mapOf("searchInternet" to ::searchInternet)
                        val imageGenerationAvailableToCall =
                            imageGenerationAvailable[functionCall.name]
                        val searchInternetAvailableToCall =
                            searchInternetAvailable[functionCall.name]
                        val imageGenerationAvailableArgs =
                            functionCall.argumentsAsJson()
                        val searchInternetAvailableArgs =
                            functionCall.argumentsAsJson()
                        if (imageGenerationAvailableToCall != null) {
                            imageGenerationAvailableToCall(
                                imageGenerationAvailableArgs.getValue("prompt").jsonPrimitive.content
                            )
                        } else if (searchInternetAvailableToCall != null) {
                            searchInternetAvailableToCall(
                                searchInternetAvailableArgs.getValue("prompt").jsonPrimitive.content
                            )
                        } else {
                            regularGPTResponse(shouldPronounce)
                        }
                    } else {
                        regularGPTResponse(shouldPronounce)
                    }
                } else {
                    regularGPTResponse(shouldPronounce)
                }
            }
        } catch (e: Exception) {
            // putMessage("", true)
            val response = when {
                e.stackTraceToString().contains("does not exist") -> {
                    "Looks like this model (${model}) is not available to you right now. It can be because of high demand or this model is currently in limited beta. If you are using a fine-tuned model, please make sure you entered correct model name. Usually model starts with 'model_name:ft-' and contains original model name, organization name and timestamp. Example: ada:ft-organization_name:model_name-YYYY-MM-DD-hh-mm-ss."
                }
                e.stackTraceToString().contains("Connect timeout has expired") || e.stackTraceToString().contains("SocketTimeoutException") -> {
                    "Could not connect to OpenAI servers. It may happen when your Internet speed is slow or too many users are using this model at the same time. Try to switch to another model."
                }
                e.stackTraceToString().contains("This model's maximum") -> {
                    "Too many tokens. It is an internal error, please report it. Also try to truncate your input. Sometimes it may help."
                }
                e.stackTraceToString().contains("No address associated with hostname") -> {
                    "You are currently offline. Please check your connection and try again."
                }
                e.stackTraceToString().contains("Incorrect API key") -> {
                    "Your API key is incorrect. Change it in Settings > Change OpenAI key. If you think this is an error please check if your API key has not been rotated. If you accidentally published your key it might be automatically revoked."
                }
                e.stackTraceToString().contains("Software caused connection abort") -> {
                    "\n\n[error] An error occurred while generating response. It may be due to a weak connection or high demand. Try to switch to another model or try again later."
                }
                e.stackTraceToString().contains("you must provide a model") -> {
                    "No valid model is set in settings. Please change the model and try again."
                }
                e.stackTraceToString().contains("You exceeded your current quota") -> {
                    "You exceeded your current quota. If you had free trial usage please add payment info. Also please check your usage limits. You can change your limits in Account settings."
                }
                else -> {
                    e.stackTraceToString()
                }
            }

            putMessage(response, true)

            adapter?.notifyDataSetChanged()

            saveSettings()

            btnAssistantVoice?.isEnabled = true
            btnAssistantSend?.isEnabled = true
            assistantLoading?.visibility = View.GONE

            showKeyboard()

            isProcessing = false
        }
    }

    private suspend fun regularGPTResponse(shouldPronounce: Boolean) {
        isProcessing = true
        disableAutoScroll = false
        assistantConversation?.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL

        var response = ""
        putMessage("", true)

        val msgs: ArrayList<ChatMessage> = chatMessages.clone() as ArrayList<ChatMessage>

        val systemMessage = preferences!!.getSystemMessage()

        if (systemMessage != "") {
            msgs.add(
                ChatMessage(
                    role = ChatRole.System,
                    content = systemMessage
                )
            )
        }

        val chatCompletionRequest = chatCompletionRequest {
            model = ModelId(this@WindowsInstantAssistant.model)
            messages = msgs
        }

        val completions: Flow<ChatCompletionChunk> =
            ai!!.chatCompletions(chatCompletionRequest)

        completions.collect { v ->
            run {
                if (v.choices[0].delta.content != null) {
                    response += v.choices[0].delta.content
                    messages[messages.size - 1]["message"] = "$response █"
                    adapter?.notifyDataSetChanged()
                }
            }
        }

        messages[messages.size - 1]["message"] = "$response\n"
        adapter?.notifyDataSetChanged()

        chatMessages.add(ChatMessage(
            role = ChatRole.Assistant,
            content = response
        ))

        pronounce(shouldPronounce, response)

        saveSettings()

        btnAssistantVoice?.isEnabled = true
        btnAssistantSend?.isEnabled = true
        assistantLoading?.visibility = View.GONE
        showKeyboard()
        isProcessing = false
    }

    private fun pronounce(st: Boolean, message: String) {
        if (st && isTTSInitialized && !silenceMode || preferences!!.getNotSilence()) {
            if (autoLangDetect) {
                languageIdentifier.identifyLanguage(message)
                    .addOnSuccessListener { languageCode ->
                        if (languageCode == "und") {
                            Log.i("MLKit", "Can't identify language.")
                        } else {
                            Log.i("MLKit", "Language: $languageCode")
                            tts!!.language = Locale.forLanguageTag(
                                languageCode
                            )
                        }

                        speak(message)
                    }.addOnFailureListener {
                        // Ignore auto language detection if an error is occurred
                        autoLangDetect = false
                        ttsPostInit()

                        speak(message)
                    }
            } else {
                speak(message)
            }
        }
    }

    private fun speak(message: String) {
        if (preferences!!.getTtsEngine() == "google") {
            tts!!.speak(message, TextToSpeech.QUEUE_FLUSH, null, "")
        } else {
            CoroutineScope(Dispatchers.Main).launch {
                val rawAudio = ai!!.speech(
                    request = SpeechRequest(
                        model = ModelId("tts-1"),
                        input = message,
                        // TODO: Replace with voice setting
                        voice = com.aallam.openai.api.audio.Voice(preferences!!.getOpenAIVoice()),
                    )
                )

                requireActivity().runOnUiThread {
                    try {
                        // create temp file that will hold byte array
                        val tempMp3 = File.createTempFile("audio", "mp3", requireActivity().cacheDir)
                        tempMp3.deleteOnExit()
                        val fos = FileOutputStream(tempMp3)
                        fos.write(rawAudio)
                        fos.close()

                        // resetting mediaplayer instance to evade problems
                        mediaPlayer?.reset()

                        // In case you run into issues with threading consider new instance like:
                        // MediaPlayer mediaPlayer = new MediaPlayer();

                        // Tried passing path directly, but kept getting
                        // "Prepare failed.: status=0x1"
                        // so using file descriptor instead
                        val fis = FileInputStream(tempMp3)
                        mediaPlayer?.setDataSource(fis.getFD())
                        mediaPlayer?.prepare()
                        mediaPlayer?.start()
                    } catch (ex: IOException) {
                        MaterialAlertDialogBuilder(requireActivity(), R.style.App_MaterialAlertDialog)
                            .setTitle("Audio error")
                            .setMessage(ex.stackTraceToString())
                            .setPositiveButton("Close") { _, _ -> }
                            .show()
                    }
                }
            }
        }
    }

    private fun writeImageToCache(bytes: ByteArray) {
        try {
            requireActivity().contentResolver.openFileDescriptor(Uri.fromFile(File(requireActivity().getExternalFilesDir("images")?.absolutePath + "/" + Hash.hash(Base64.getEncoder().encodeToString(bytes)) + ".png")), "w")?.use {
                FileOutputStream(it.fileDescriptor).use {
                    it.write(
                        bytes
                    )
                }
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private suspend fun generateImage(p: String) {
        isProcessing = true
        assistantConversation?.setOnTouchListener(null)
        assistantConversation?.visibility = View.VISIBLE
        btnSaveToChat?.visibility = View.VISIBLE

        disableAutoScroll = false
        assistantConversation?.transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL

        try {
            val images = ai?.imageURL(
                creation = ImageCreation(
                    prompt = p,
                    n = 1,
                    model = ModelId("dall-e-${preferences!!.getDalleVersion()}"),
                    size = ImageSize(resolution)
                )
            )

            val url = URL(images?.get(0)?.url!!)

            val `is` = withContext(Dispatchers.IO) {
                url.openStream()
            }

            Thread {
                val bytes: ByteArray = org.apache.commons.io.IOUtils.toByteArray(`is`)

                writeImageToCache(bytes)

                val encoded = Base64.getEncoder().encodeToString(bytes)

                val path = "data:image/png;base64,$encoded"

                requireActivity().runOnUiThread {
                    putMessage(path, true)

                    assistantConversation?.setOnTouchListener { _, event -> run {
                        if (event.action == MotionEvent.ACTION_SCROLL || event.action == MotionEvent.ACTION_UP) {
                            assistantConversation?.transcriptMode = ListView.TRANSCRIPT_MODE_DISABLED
                            disableAutoScroll = true
                        }
                        return@setOnTouchListener false
                    } }

                    saveSettings()

                    btnAssistantVoice?.isEnabled = true
                    btnAssistantSend?.isEnabled = true
                    assistantLoading?.visibility = View.GONE
                    showKeyboard()
                    isProcessing = false
                }
            }.start()
        } catch (e: Exception) {
            when {
                e.stackTraceToString().contains("Your request was rejected") -> {
                    putMessage("Your prompt contains inappropriate content and can not be processed. We strive to make AI safe and relevant for everyone.", true)
                }
                e.stackTraceToString().contains("No address associated with hostname") -> {
                    putMessage("You are currently offline. Please check your connection and try again.", true);
                }
                e.stackTraceToString().contains("Incorrect API key") -> {
                    putMessage("Your API key is incorrect. Change it in Settings > Change OpenAI key. If you think this is an error please check if your API key has not been rotated. If you accidentally published your key it might be automatically revoked.", true);
                }
                e.stackTraceToString().contains("Software caused connection abort") -> {
                    putMessage("An error occurred while generating response. It may be due to a weak connection or high demand. Try again later.", true);
                }
                e.stackTraceToString().contains("You exceeded your current quota") -> {
                    putMessage("You exceeded your current quota. If you had free trial usage please add payment info. Also please check your usage limits. You can change your limits in Account settings.", true)
                }
                else -> {
                    putMessage(e.stackTraceToString(), true)
                }
            }

            saveSettings()

            btnAssistantVoice?.isEnabled = true
            btnAssistantSend?.isEnabled = true
            assistantLoading?.visibility = View.GONE
            showKeyboard()
            isProcessing = false
        }
    }

    private fun saveSettings() {
        if (chatID != "") {
            val chat = requireActivity().getSharedPreferences(
                "chat_$chatID",
                FragmentActivity.MODE_PRIVATE
            )
            val editor = chat.edit()
            val gson = Gson()
            val json: String = gson.toJson(messages)

            if (json == "") editor.putString("chat", "[]")
            else editor.putString("chat", json)

            editor.apply()
        }
        isProcessing = false
    }

    private fun save(id: String) {
        chatID = id
        saveSettings()
        btnSaveToChat?.text = "Saved"
        btnSaveToChat?.isEnabled = false
        btnSaveToChat?.setIconResource(R.drawable.ic_done)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        preferences = Preferences.getPreferences(requireActivity(), "")

        mediaPlayer = MediaPlayer()

        languageIdentifier = LanguageIdentification.getClient()

        requireActivity().window?.navigationBarColor = SurfaceColors.SURFACE_1.getColor(requireActivity())

        btnAssistantVoice = view.findViewById(R.id.btn_assistant_voice)
        btnAssistantSettings = view.findViewById(R.id.btn_assistant_settings)
        btnAssistantShowKeyboard = view.findViewById(R.id.btn_assistant_show_keyboard)
        btnAssistantHideKeyboard = view.findViewById(R.id.btn_assistant_hide_keyboard)
        btnAssistantSend = view.findViewById(R.id.btn_assistant_send)
        btnSaveToChat = view.findViewById(R.id.btn_save)
        assistantMessage = view.findViewById(R.id.assistant_message)
        assistantInputLayout = view.findViewById(R.id.input_layout)
        assistantActionsLayout = view.findViewById(R.id.assistant_actions)
        assistantConversation = view.findViewById(R.id.assistant_conversation)
        assistantLoading = view.findViewById(R.id.assistant_loading)

        btnAssistantVoice?.setImageResource(R.drawable.ic_microphone_v2)
        btnAssistantSettings?.setImageResource(R.drawable.ic_settings)
        btnAssistantShowKeyboard?.setImageResource(R.drawable.ic_keyboard)
        btnAssistantHideKeyboard?.setImageResource(R.drawable.ic_keyboard_hide)
        btnAssistantSend?.setImageResource(R.drawable.ic_send)

        assistantConversation?.isNestedScrollingEnabled = true
        assistantMessage?.isNestedScrollingEnabled = true

        initSettings()

        btnAssistantShowKeyboard?.setOnClickListener {
            showKeyboard()
        }

        btnAssistantHideKeyboard?.setOnClickListener {
            hideKeyboard()
        }

        assistantMessage?.setOnKeyListener { v, keyCode, event -> run {
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER && event.isShiftPressed) {
                (v as EditText).append("\n")
                return@run true
            } else if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                parseMessage((v as EditText).text.toString())
                return@run true
            } else if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_S && event.isCtrlPressed) {
                val chatDialogFragment: AddChatDialogFragment = AddChatDialogFragment.newInstance("", false, true, true)
                chatDialogFragment.setStateChangedListener(chatListUpdatedListener)
                chatDialogFragment.show(parentFragmentManager.beginTransaction(), "AddChatDialog")
                return@run true
            } else if (event.action == KeyEvent.ACTION_DOWN && ((keyCode == KeyEvent.KEYCODE_ESCAPE && event.isShiftPressed) || keyCode == KeyEvent.KEYCODE_BACK)) {
                requireActivity().finish()
                return@run true
            }
            return@run false
        }}

        btnSaveToChat?.setOnClickListener {
            val chatDialogFragment: AddChatDialogFragment = AddChatDialogFragment.newInstance("", false, true, true)
            chatDialogFragment.setStateChangedListener(chatListUpdatedListener)
            chatDialogFragment.show(parentFragmentManager.beginTransaction(), "AddChatDialog")
        }

        hideKeyboard()
    }

    private fun hideKeyboard() {
        assistantActionsLayout?.visibility = View.VISIBLE
        assistantInputLayout?.visibility = View.INVISIBLE
        assistantMessage?.isEnabled = false
        btnAssistantSend?.isEnabled = false
        btnAssistantHideKeyboard?.isEnabled = false
        btnAssistantVoice?.visibility = View.VISIBLE
    }

    private fun showKeyboard() {
        assistantLoading?.visibility = View.GONE
        assistantActionsLayout?.visibility = View.GONE
        assistantInputLayout?.visibility = View.VISIBLE
        assistantMessage?.isEnabled = true
        btnAssistantSend?.isEnabled = true
        btnAssistantHideKeyboard?.isEnabled = true
        btnAssistantVoice?.visibility = View.GONE

        assistantMessage?.requestFocus()
    }

    private fun run(prompt: String) {
        parseMessage(prompt)
    }
}
