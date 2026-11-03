package com.blurrer.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.blurrer.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val finder = FaceFinder()
    private val photos = mutableListOf<Photo>()
    private var current = -1
    private var settings = Settings()
    private var renderJob: Job? = null
    private var busy = false
    private var pendingCameraUri: Uri? = null
    private val queued = mutableListOf<Uri>()

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) addPhotos(listOf(uri), cameraShot = true)
    }

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) addPhotos(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        b.root.clipToPadding = false

        // Give the photo most of the screen height.
        b.root.post {
            val h = (resources.displayMetrics.heightPixels * 0.52f).roundToInt()
            b.stage.layoutParams = b.stage.layoutParams.apply { height = max(h, (280 * resources.displayMetrics.density).roundToInt()) }
        }

        b.takePhoto.setOnClickListener { openCamera() }
        b.choosePhotos.setOnClickListener {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.save.setOnClickListener { saveCurrent() }
        b.saveAll.setOnClickListener { saveAll() }
        b.share.setOnClickListener { shareCurrent() }

        b.editor.onFacesChanged = { rerender(); updateClearButton() }
        b.clearAll.setOnClickListener { clearOrFind() }
        buildSolidSwatches()

        // Four styles share one row, so keep their padding tight on narrow phones.
        val pad = (4 * resources.displayMetrics.density).roundToInt()
        for (i in 0 until b.styleGroup.childCount) {
            (b.styleGroup.getChildAt(i) as com.google.android.material.button.MaterialButton).apply {
                setPadding(pad, paddingTop, pad, paddingBottom)
                textSize = 13f
                maxLines = 1
            }
        }
        b.styleGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val style = when (id) {
                R.id.stylePixel -> BlurStyle.PIXEL
                R.id.styleSolid -> BlurStyle.SOLID
                R.id.styleFaceless -> BlurStyle.FACELESS
                else -> BlurStyle.BLUR
            }
            settings = settings.copy(style = style)
            b.strength.isEnabled = style == BlurStyle.BLUR || style == BlurStyle.PIXEL
            b.solidColors.visibility = if (style == BlurStyle.SOLID) View.VISIBLE else View.GONE
            rerender(all = true)
        }
        val strengthNames = arrayOf("", "Light", "Medium", "Strong", "Very strong", "Maximum")
        b.strengthValue.text = strengthNames[settings.strength]
        b.strength.addOnChangeListener { _, value, _ ->
            settings = settings.copy(strength = value.toInt())
            b.strengthValue.text = strengthNames[settings.strength]
            rerender(all = true)
        }
        val detectionNames = arrayOf("", "Standard", "Thorough", "Catch everything")
        b.detectionValue.text = detectionNames[settings.detection]
        b.detection.addOnChangeListener { _, value, fromUser ->
            settings = settings.copy(detection = value.toInt())
            b.detectionValue.text = detectionNames[settings.detection]
            if (fromUser) redetectAll()
        }
        b.outlines.setOnCheckedChangeListener { _, on ->
            settings = settings.copy(outlines = on)
            b.editor.showOutlines = on
        }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Photos shared into Blurrer from the gallery or another app. */
    private fun handleShareIntent(i: Intent?) {
        i ?: return
        var uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        // Some apps only put the photo in the clip data.
        if (uris.isEmpty()) i.clipData?.let { c -> uris = (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }
        if (uris.isNotEmpty()) {
            i.action = null
            addPhotos(uris)
        }
    }

    // ---------- adding photos ----------

    private fun openCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        pendingCameraUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("No camera app was found on this phone.")
        }
    }

    private fun addPhotos(uris: List<Uri>, cameraShot: Boolean = false) {
        if (busy) { queued += uris; return }
        lifecycleScope.launch {
            setBusy(true, "Finding faces…")
            val firstNew = photos.size
            uris.forEachIndexed { i, uri ->
                if (uris.size > 1) setBusy(true, "Finding faces in photo ${i + 1} of ${uris.size}…")
                val photo = withContext(Dispatchers.Default) {
                    try {
                        val bmp = decode(uri)
                        val name = if (cameraShot) "photo-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        else displayName(uri)
                        Photo(name, bmp, finder.find(bmp, settings.detection))
                    } catch (e: Exception) { null }
                }
                if (photo == null) toast("Couldn't open one of the photos.") else photos += photo
            }
            setBusy(false)
            if (photos.size > firstNew) {
                buildStrip()
                select(firstNew)
            }
        }
    }

    /** Decodes with the EXIF rotation applied, capped at 4096 px on the long side. */
    private fun decode(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width; val h = info.size.height
            val s = 4096f / max(w, h)
            if (s < 1f) decoder.setTargetSize((w * s).roundToInt(), (h * s).roundToInt())
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun displayName(uri: Uri): String {
        var name = "photo"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (_: Exception) { }
        return name.substringBeforeLast('.').take(80).ifBlank { "photo" }
    }

    private fun redetectAll() {
        if (photos.isEmpty() || busy) return
        lifecycleScope.launch {
            setBusy(true, "Finding faces again…")
            for (p in photos) {
                val manual = p.faces.filter { it.manual }
                val found = withContext(Dispatchers.Default) { finder.find(p.bitmap, settings.detection) }
                p.faces = (found + manual).toMutableList()
            }
            setBusy(false)
            rerender(all = true)
            updateClearButton()
        }
    }

    // ---------- showing photos ----------

    private fun select(i: Int) {
        current = i
        b.empty.visibility = View.GONE
        b.status.visibility = View.VISIBLE
        b.save.isEnabled = true
        b.share.isEnabled = true
        b.editor.photo = photos[i]
        b.clearAll.visibility = View.VISIBLE
        updateClearButton()
        markSelectedThumb()
        rerender()
    }

    private fun rerender(all: Boolean = false) {
        val p = photos.getOrNull(current) ?: return
        val s = settings
        val idx = current
        updateCount()
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val out = withContext(Dispatchers.Default) { Blur.render(p, s) }
            if (current == idx) b.editor.rendered = out
            updateThumb(idx, out)
            if (all) photos.indices.filter { it != idx }.forEach { j ->
                val o = withContext(Dispatchers.Default) { Blur.render(photos[j], s) }
                updateThumb(j, o)
            }
        }
    }

    private fun updateCount() {
        val n = photos.getOrNull(current)?.faces?.count { it.on } ?: 0
        b.count.text = if (n == 1) "1 face blurred" else "$n faces blurred"
    }

    private fun buildStrip() {
        val many = photos.size > 1
        b.stripScroll.visibility = if (many) View.VISIBLE else View.GONE
        b.saveAll.visibility = if (many) View.VISIBLE else View.GONE
        b.strip.removeAllViews()
        val d = resources.displayMetrics.density
        photos.forEachIndexed { i, p ->
            val frame = FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams((76 * d).toInt(), (76 * d).toInt()).apply {
                    marginEnd = (8 * d).toInt()
                }
                setOnClickListener { select(i) }
                contentDescription = "Photo ${i + 1}"
                tag = i
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(thumbOf(p.bitmap))
                clipToOutline = true
                background = ContextCompat.getDrawable(context, R.drawable.stage_bg)
            }
            val badge = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt()); textSize = 11f
                background = ContextCompat.getDrawable(context, R.drawable.badge_bg)
                setPadding((6 * d).toInt(), (1 * d).toInt(), (6 * d).toInt(), (1 * d).toInt())
                text = p.faces.count { it.on }.toString()
            }
            val border = View(this)
            frame.addView(img, FrameLayout.LayoutParams(-1, -1))
            frame.addView(border, FrameLayout.LayoutParams(-1, -1))
            frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
                setMargins(0, 0, (4 * d).toInt(), (4 * d).toInt())
            })
            b.strip.addView(frame)
        }
        markSelectedThumb()
    }

    private fun thumbOf(bmp: Bitmap): Bitmap {
        val s = 240f / max(bmp.width, bmp.height)
        return Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
    }

    private fun updateThumb(i: Int, rendered: Bitmap) {
        val frame = b.strip.getChildAt(i) as? FrameLayout ?: return
        (frame.getChildAt(0) as ImageView).setImageBitmap(thumbOf(rendered))
        (frame.getChildAt(2) as TextView).text = photos[i].faces.count { it.on }.toString()
    }

    private fun markSelectedThumb() {
        for (j in 0 until b.strip.childCount) {
            val frame = b.strip.getChildAt(j) as FrameLayout
            frame.getChildAt(1).background =
                if (j == current) ContextCompat.getDrawable(this, R.drawable.thumb_selected) else null
        }
    }

    // ---------- clear all / find faces ----------

    private fun updateClearButton() {
        val p = photos.getOrNull(current) ?: return
        b.clearAll.setText(if (p.faces.isEmpty()) R.string.find_faces else R.string.clear_all)
    }

    /** Removes every box on this photo; when there are none left, looks for faces again. */
    private fun clearOrFind() {
        val p = photos.getOrNull(current) ?: return
        if (p.faces.isNotEmpty()) {
            p.faces.clear()
            b.editor.invalidate()
            rerender(); updateClearButton()
            return
        }
        if (busy) return
        lifecycleScope.launch {
            setBusy(true, "Finding faces…")
            p.faces = withContext(Dispatchers.Default) { finder.find(p.bitmap, settings.detection) }
            setBusy(false)
            rerender(); updateClearButton()
        }
    }

    // ---------- solid colour ----------

    private val solidChoices = intArrayOf(
        0xFF14161B.toInt(), 0xFFFFFFFF.toInt(), 0xFF8A8F98.toInt(), 0xFFD93025.toInt(),
        0xFFF9AB00.toInt(), 0xFF188038.toInt(), 0xFF1A56DB.toInt(), 0xFF9334E6.toInt(),
    )

    private fun buildSolidSwatches() {
        val d = resources.displayMetrics.density
        b.solidColors.removeAllViews()
        solidChoices.forEach { color ->
            val v = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply {
                    marginEnd = (10 * d).toInt()
                }
                contentDescription = "Solid colour"
                setOnClickListener {
                    settings = settings.copy(solidColor = color)
                    paintSwatches()
                    rerender(all = true)
                }
                tag = color
            }
            b.solidColors.addView(v)
        }
        paintSwatches()
    }

    private fun paintSwatches() {
        val d = resources.displayMetrics.density
        val ring = ContextCompat.getColor(this, R.color.accent)
        val edge = ContextCompat.getColor(this, R.color.muted)
        for (i in 0 until b.solidColors.childCount) {
            val v = b.solidColors.getChildAt(i)
            val color = v.tag as Int
            val selected = color == settings.solidColor
            v.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                if (selected) setStroke((3 * d).toInt(), ring) else setStroke((1 * d).toInt(), edge)
            }
            v.scaleX = if (selected) 1.12f else 1f
            v.scaleY = v.scaleX
        }
    }

    // ---------- saving and sharing ----------

    private suspend fun saveToGallery(p: Photo): Boolean = withContext(Dispatchers.IO) {
        val out = Blur.render(p, settings)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${p.name}-blurred.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Blurrer")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return@withContext false
        try {
            contentResolver.openOutputStream(uri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: throw IllegalStateException("no stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    private fun saveCurrent() {
        val p = photos.getOrNull(current) ?: return
        lifecycleScope.launch {
            if (saveToGallery(p)) toast("Saved to Pictures/Blurrer") else toast("Couldn't save the photo. Check your storage space.")
        }
    }

    private fun saveAll() {
        if (photos.isEmpty()) return
        lifecycleScope.launch {
            setBusy(true, "Saving…")
            var n = 0
            for (p in photos) if (saveToGallery(p)) n++
            setBusy(false)
            toast("Saved $n of ${photos.size} photos to Pictures/Blurrer")
        }
    }

    private fun shareCurrent() {
        val p = photos.getOrNull(current) ?: return
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "${p.name}-blurred.jpg")
                file.outputStream().use { Blur.render(p, settings).compress(Bitmap.CompressFormat.JPEG, 92, it) }
                FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share blurred photo"))
        }
    }

    // ---------- helpers ----------

    private fun setBusy(on: Boolean, text: String? = null) {
        busy = on
        b.busy.visibility = if (on) View.VISIBLE else View.GONE
        text?.let { b.busyText.text = it }
        b.takePhoto.isEnabled = !on
        b.choosePhotos.isEnabled = !on
        b.saveAll.isEnabled = !on
        // Photos shared in while we were busy start as soon as we're free.
        if (!on && queued.isNotEmpty()) b.root.post {
            if (!busy && queued.isNotEmpty()) { val next = queued.toList(); queued.clear(); addPhotos(next) }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
