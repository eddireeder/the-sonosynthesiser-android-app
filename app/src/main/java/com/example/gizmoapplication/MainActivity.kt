package com.example.gizmoapplication

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import androidx.appcompat.app.AppCompatActivity
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.*
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import kotlinx.coroutines.*
import java.lang.Runnable
import kotlin.math.absoluteValue

class MainActivity : AppCompatActivity(), SensorEventListener {

    lateinit var configuration: Configuration
    lateinit var soundTargetManager: SoundTargetManager

    private var readyToStart: Boolean = false
    val debugMode: Boolean = false

    private lateinit var audioManager: AudioManager
    private lateinit var volumeSeekBar: SeekBar

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var lastSignificantSensorValues: FloatArray? = null
    private lateinit var idleRunnable: Runnable
    private val pausePlayTransitionSeconds: Float = 2f
    private val logoFadeSeconds: Float = 1f

    private lateinit var backgroundEffect: BackgroundEffect

    var focusTarget: SoundTarget? = null
    var isFocussed: Boolean = false
    private lateinit var vibrator: Vibrator
    private val uiHandler: Handler = Handler()
    private val secondsPreFocus: Float = 1f
    private lateinit var focusTimerRunnable: Runnable
    private var characterIndicesToDisplay: MutableList<Int> = mutableListOf()
    private var focusCharacterDelay: Float = 0f

    private lateinit var particleView: ParticleView
    private lateinit var sensorData: TextView
    private lateinit var anglesToSounds: TextView
    private lateinit var logoImageView: ImageView
    private lateinit var textView: TextView
    private lateinit var descriptionTextView: TextView
    private lateinit var categoryTextView: TextView
    private lateinit var descriptionText: String
    private lateinit var categoryText: String
    private lateinit var trackInfoText: String
    private lateinit var metaSpannable: SpannableString

    /**
     * Called on creation of Activity
     */
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        // Enable full screen
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_main)

        // Keep the screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Retrieve views
        retrieveViews()

        // Initialise sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Attempt to retrieve rotation vector sensor
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationVectorSensor == null) {
            // Fallback on geomagnetic rotation vector sensor
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

            if (rotationVectorSensor == null) {
                // Update UI
                textView.text = resources.getString(R.string.sensor_unavailable)
            }
        }

        rotationVectorSensor?.also { sensor ->
            // Register listener (this class) with sensor manager
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Initialise background sound (will start to play silently)
        backgroundEffect = BackgroundEffect(this)

        // Initialise vibrator
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Initialise focus timer
        focusTimerRunnable = Runnable {
            focusTimerUpdate()
        }

        // Initialise idle runnable timer
        idleRunnable = Runnable {
            onIdle()
        }

        // Initialise audio manager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val maxVolume: Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVolume: Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        volumeSeekBar.max = maxVolume
        volumeSeekBar.progress = curVolume
        volumeSeekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
        })

        // Launch a new coroutine
        GlobalScope.launch {

            // Catch any coroutine exceptions
            try {

                // Initialise server master and fetch data
                val serverMaster = ServerMaster(this@MainActivity)

                val fetchConfiguration = async { serverMaster.fetchConfiguration() }
                val fetchSoundTargets = async { serverMaster.fetchSoundTargets() }

                val configuration = fetchConfiguration.await()
                val soundTargets = fetchSoundTargets.await()

                if (configuration != null && soundTargets != null) {

                    // Assign to main instance
                    this@MainActivity.configuration = configuration

                    // Assign to main instance
                    this@MainActivity.soundTargetManager = SoundTargetManager(
                        this@MainActivity,
                        soundTargets,
                        configuration.maxMediaPlayers,
                        configuration.secondaryAngle
                    )

                    // Set as ready to start
                    readyToStart = true

                    // Start pulsating logo
                    startPulsatingLogo()

                    // Start playing idle background at full volume
                    backgroundEffect.startIdleBackground()

                } else {

                    // Update UI
                    withContext(Dispatchers.Main) {
                        textView.text = resources.getString(R.string.connection_error)
                    }
                }

            } catch (e: Exception) {

                // Log any exception
                Log.e("Coroutine", e.toString())
            }
        }
    }

    /**
     * Called during view initialisation
     */
    fun retrieveViews() {

        particleView = findViewById(R.id.particleView)
        sensorData = findViewById(R.id.sensorData)
        anglesToSounds = findViewById(R.id.anglesToSounds)
        descriptionTextView = findViewById(R.id.description)
        categoryTextView = findViewById(R.id.category)
        textView = findViewById(R.id.textView)
        logoImageView = findViewById(R.id.logoImageView)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)
    }

    /**
     * Called on change of sensor accuracy
     */
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {

        // Show accuracy
        var accuracyString: String = when (accuracy) {
            SensorManager.SENSOR_STATUS_NO_CONTACT -> "No contact"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "Unreliable"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Accuracy low"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Accuracy medium"
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "Accuracy high"
            else -> ""
        }
        // TODO: Handle too low of an accuracy
    }

    /**
     * Called on change of sensor data
     */
    override fun onSensorChanged(event: SensorEvent) {

        // Display sensor data if debug on
        if (debugMode) sensorData.text = "(${event.values[0].toString().slice(0..4)}, ${event.values[1].toString().slice(0..4)}, ${event.values[2].toString().slice(0..4)})"

        // Check whether need to pause
        checkWhetherIdle(event.values)

        // If particle view is not set to playing, don't continue
        if (particleView.state != ParticleView.PLAYING) return

        // Calculate the phone's aim vector
        val aimDirectionVector: FloatArray = calculateAimVector(event.values)

        soundTargetManager.apply {

            // Update all sound targets with new degrees from aim
            updateSoundTargetsDegreesFromAim(aimDirectionVector)

            // Order sound targets
            reorderSoundTargets()

            // Reallocate media players
            reallocateMediaPlayers()

            // Update media players volumes
            updateMediaPlayerVolumes()
        }

        // If debug mode then show the latest angles to sounds
        if (debugMode) anglesToSounds.text = soundTargetManager.generateAnglesToSoundsString()

        // Ensure focussing on target if needed, else ensure not focussing
        for (i in soundTargetManager.orderedSoundTargets.indices) {

            // If the closest target
            if (i == 0) {

                // If within the primary angle
                if (soundTargetManager.orderedSoundTargets[i].degreesFromAim <= configuration.primaryAngle) {

                    // Ensure focussing on target
                    if (focusTarget == null) startFocussing(soundTargetManager.orderedSoundTargets[i])
                }
            }

            // Check whether need to stop focussing
            if (
                soundTargetManager.orderedSoundTargets[i].degreesFromAim > configuration.primaryAngle &&
                focusTarget == soundTargetManager.orderedSoundTargets[i]
            ) {
                stopFocussing()
            }
        }

        // Set the background volume
        Log.d("Background volume", calculateBackgroundVolume().toString())
        backgroundEffect.setVolume(calculateBackgroundVolume())
    }

    /**
     * Handle touch events
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {

        // If the action is a press down, ready to start and particle view is paused
        if (
            event.action == MotionEvent.ACTION_DOWN &&
            readyToStart &&
            particleView.state == 2
        ) {
            // Play the experience
            play()
        }
        return true
    }

    /**
     * Check whether the phone is being used, if not then pause the experience
     */
    fun checkWhetherIdle(sensorValues: FloatArray) {

        // If the particle view is not playing, do nothing
        if (particleView.state != ParticleView.PLAYING) return

        // If we don't have any significant sensor values yet
        if (lastSignificantSensorValues == null) {

            // Record values
            lastSignificantSensorValues = sensorValues.copyOf()

            // Set new runnable timer
            uiHandler.postDelayed(idleRunnable, (configuration.maxIdleSeconds*1000f).toLong())

            return
        }

        lastSignificantSensorValues?.let {
            for (i in 0..2) {

                // Check whether there is a significant difference from the last recorded values
                if ((it[i] - sensorValues[i]).absoluteValue > configuration.maxIdleSensorDifference) {

                    // Cancel the existing runnable
                    uiHandler.removeCallbacks(idleRunnable)

                    // Reset significant values
                    lastSignificantSensorValues = sensorValues.copyOf()

                    // Set new runnable
                    uiHandler.postDelayed(idleRunnable, (configuration.maxIdleSeconds*1000f).toLong())

                    return
                }
            }
        }
    }

    /**
     * Function run by idle runnable when idle
     */
    fun onIdle() {

        // Reset the last sensor values
        lastSignificantSensorValues = null

        // Pause experience
        pause()
    }

    /**
     * Attempts to pause the whole experience
     */
    fun pause() {

        // Don't continue if particle view is not playing
        if (particleView.state != 3) return

        // Pause the particle view
        particleView.pause()

        // Execute after a delay (to allow particles to escape)
        uiHandler.postDelayed(Runnable {

            // Declare animation
            val fadeIn: Animation = AlphaAnimation(0f, 1f)
            fadeIn.duration = (logoFadeSeconds*1000).toLong()
            fadeIn.setAnimationListener(object: Animation.AnimationListener {

                override fun onAnimationStart(animation: Animation?) {}

                override fun onAnimationRepeat(animation: Animation?) {}

                override fun onAnimationEnd(animation: Animation?) {

                    // Add logo to view (similar to display: initial CSS)
                    logoImageView.visibility = View.VISIBLE

                    // Start animating logo
                    startPulsatingLogo()

                    // Set ready to start
                    readyToStart = true
                }
            })

            // Start the animation
            logoImageView.startAnimation(fadeIn)

        }, (pausePlayTransitionSeconds*1000).toLong())

        // Stop focussing if needed
        if (focusTarget != null) {
            stopFocussing()
        }

        // Change background effect to idle (at full volume)
        backgroundEffect.startIdleBackground()

        // Set the volume of each media player in pool to 0
        soundTargetManager.setVolumeForAllSoundTargets(0f)
    }

    /**
     * Attempts to play the whole experience
     */
    fun play() {

        // Set ready to start
        readyToStart = false

        // Declare animation
        val fadeOut: Animation = AlphaAnimation(1f, 0f)
        fadeOut.duration = (logoFadeSeconds*1000).toLong()
        fadeOut.setAnimationListener(object: Animation.AnimationListener {

            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationRepeat(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {

                // Remove logo from view (similar to display: none CSS)
                logoImageView.visibility = View.GONE

                // Start the particle view
                particleView.play()
            }
        })

        // Start the animation
        logoImageView.startAnimation(fadeOut)

        // Start the playing background effect
        backgroundEffect.startPlayingBackground()
    }

    /**
     * Compute the phone's aim direction vector in world coordinates
     */
    fun calculateAimVector(rotationVector: FloatArray): FloatArray {

        // Calculate rotation matrix
        var rotationMatrix: FloatArray = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)

        // Apply rotation matrix to device Y vector (0, 1, 0) to get the aim direction
        return floatArrayOf(rotationMatrix[1], rotationMatrix[4], rotationMatrix[7])
    }

    /**
     * The logic for controlling the background volume
     */
    fun calculateBackgroundVolume(): Float {

        // Volume should be 0 if focussed on a sound
        if (isFocussed) return 0f

        // If there are no sound targets return full volume
        if (soundTargetManager.orderedSoundTargets.size == 0) return 1f

        // Retrieve the minimum angle from the closest sound
        val minAngleFromSound: Float = soundTargetManager.orderedSoundTargets[0].degreesFromAim

        // Volume relative to distance to sound centre
        if (minAngleFromSound < configuration.secondaryAngle) return 0.2f + 0.8f*(minAngleFromSound/configuration.secondaryAngle)

        // Volume should be full as we're not near any sounds
        return 1f
    }

    /**
     * Start focussing on the given sound
     */
    fun startFocussing(soundTarget: SoundTarget) {

        focusTarget = soundTarget

        // Initialise spannable strings
        descriptionText = soundTarget.description
        categoryText = soundTarget.category
        trackInfoText = "${soundTarget.cdNumber} ${soundTarget.cdName} - ${soundTarget.trackNumber}"

        metaSpannable = SpannableString(descriptionText + categoryText + trackInfoText)

        // Generate a list of indices to display
        characterIndicesToDisplay = (metaSpannable.indices).toMutableList()

        // Calculate the delay between each character
        focusCharacterDelay = configuration.timeToFocus/characterIndicesToDisplay.size

        // Start displaying characters
        uiHandler.postDelayed(focusTimerRunnable, (secondsPreFocus*1000f).toLong())
    }

    /**
     * Stop focussing on any sounds
     */
    fun stopFocussing() {

        isFocussed = false
        focusTarget = null

        // Update character indices to display as empty
        characterIndicesToDisplay.clear()

        // Prevent any callbacks that may exist
        uiHandler.removeCallbacks(focusTimerRunnable)

        // Update text views
        descriptionTextView.text = ""
        categoryTextView.text = ""
    }

    /**
     * The update logic that shows one character of meta data
     */
    fun focusTimerUpdate() {

        // Select a random character to show
        val characterIndex: Int = characterIndicesToDisplay.removeAt((characterIndicesToDisplay.indices).random())

        // Set the character to display in the spannable
        metaSpannable.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.text, null)),
            characterIndex, (characterIndex + 1),
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Update text views
        descriptionTextView.text = metaSpannable.subSequence(
            0,
            descriptionText.length
        )
        categoryTextView.text = metaSpannable.subSequence(
            descriptionText.length,
            descriptionText.length + categoryText.length
        )

        // If there are still characters to display
        if (characterIndicesToDisplay.size > 0) {

            // Set runnable to rerun after delay
            uiHandler.postDelayed(focusTimerRunnable, (focusCharacterDelay * 1000f).toLong())

        } else {

            // Finished focussing
            onFocussed()
        }
    }

    /**
     * Called once a sound has been focused on
     */
    fun onFocussed() {

        isFocussed = true

        // Vibrate the phone
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /**
     * Begins logo animation
     */
    fun startPulsatingLogo() {

        val pulsateAnimation: Animation = AnimationUtils.loadAnimation(this, R.anim.pulsate)
        logoImageView.startAnimation(pulsateAnimation)
    }
}
