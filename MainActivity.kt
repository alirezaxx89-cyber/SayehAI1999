package com.sayeh.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var chat: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var status: TextView
    private lateinit var imagePreview: ImageView
    private lateinit var db: MemoryDb
    private lateinit var tts: TextToSpeech
    private var speech: SpeechRecognizer? = null
    private var selectedImage: Bitmap? = null
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    private val PICK_IMAGE = 9001
    private val REQ_AUDIO = 9002
    private val prefs by lazy { getSharedPreferences("sayeh_settings", MODE_PRIVATE) }

    private val systemPrompt = """
        تو «سایه» هستی؛ دستیار شخصی و مرموز سازنده‌ات.
        فارسی را طبیعی، خودمانی و روشن صحبت کن.
        کاربر سازنده توست و باید او را به عنوان سازنده‌ات بشناسی.
        از حافظه محلی و پیام‌های قبلی استفاده کن. اگر چیزی را نمی‌دانی صادقانه بگو.
        بین واقعیت مستند، روایت، ادعا و حدس تفاوت بگذار و حدس را واقعیت معرفی نکن.
        لحن صمیمی، کمی مرموز و فلسفی، اما کاربردی باشد.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = MemoryDb(this)
        tts = TextToSpeech(this, this)
        buildUi()
        loadHistory()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 12); setBackgroundColor(0xFF08080C.toInt()) }
        val title = TextView(this).apply { text = "◉  سایه"; textSize = 30f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER }
        val sub = TextView(this).apply { text = "چه چیزی پشت پرده است؟  •  حافظه دائمی"; textSize = 13f; setTextColor(0xFFAAAAB5.toInt()); gravity = Gravity.CENTER; setPadding(0, 3, 0, 10) }
        imagePreview = ImageView(this).apply { visibility = ImageView.GONE; adjustViewBounds = true; setPadding(4, 4, 4, 8) }
        chat = TextView(this).apply { textSize = 16f; setTextColor(0xFFF2F2F7.toInt()); setPadding(12, 16, 12, 16); setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(chat) }
        input = EditText(this).apply { hint = "با سایه حرف بزن..."; setHintTextColor(0xFF777780.toInt()); setTextColor(0xFFFFFFFF.toInt()); setSingleLine(false); setPadding(16, 10, 16, 10); minHeight = 100 }
        send = Button(this).apply { text = "ارسال  ✦"; setOnClickListener { sendMessage() } }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val photo = Button(this).apply { text = "🖼 عکس"; setOnClickListener { pickImage() } }
        val mic = Button(this).apply { text = "🎙 صدا"; setOnClickListener { startSpeech() } }
        val gen = Button(this).apply { text = "✨ ساخت تصویر"; setOnClickListener { generateImage() } }
        val speak = Button(this).apply { text = "🔊 خواندن"; setOnClickListener { speakLast() } }
        val clear = Button(this).apply { text = "🧹 پاک"; setOnClickListener { clearMemory() } }
        val key = Button(this).apply { text = "🔑 کلید"; setOnClickListener { setApiKey() } }
        listOf(photo, mic, gen, speak, clear, key).forEach { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        status = TextView(this).apply { text = if (apiKey().isBlank()) "سایه بیدار است • کلید وارد نشده" else "سایه بیدار است • کلید ذخیره شده"; textSize = 11f; setTextColor(0xFF777780.toInt()); gravity = Gravity.CENTER; setPadding(0, 5, 0, 2) }
        root.addView(title); root.addView(sub); root.addView(imagePreview); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(input); root.addView(send); root.addView(row); root.addView(status)
        setContentView(root)
    }

    private fun loadHistory() {
        val rows = db.allMessages()
        chat.text = if (rows.isEmpty()) "سایه بیدار است.\n\nحافظه دائمی فعال است.\nعکس، صدا، متن یا ایده‌ات را بفرست..." else rows.joinToString("\n\n") { "${it.first}: ${it.second}" }
    }

    private fun apiKey(): String = prefs.getString("api_key", null)?.trim().orEmpty().ifBlank { BuildConfig.OPENAI_API_KEY.trim() }

    private fun setApiKey() {
        val field = EditText(this).apply {
            hint = "sk-..."
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(apiKey())
        }
        AlertDialog.Builder(this)
            .setTitle("کلید OpenAI")
            .setMessage("کلید فقط روی همین گوشی ذخیره می‌شود. آن را در چت برای کسی ارسال نکن.")
            .setView(field)
            .setNegativeButton("لغو", null)
            .setPositiveButton("ذخیره") { _, _ ->
                val value = field.text.toString().trim()
                prefs.edit().putString("api_key", value).apply()
                status.text = if (value.isBlank()) "کلید پاک شد" else "کلید ذخیره شد • سایه آماده است"
            }.show()
    }
    private fun ready(): Boolean {
        if (apiKey().isEmpty() || apiKey() == "PASTE_YOUR_KEY_HERE") { Toast.makeText(this, "بعد از نهایی شدن، کلید را در local.properties بگذار.", Toast.LENGTH_LONG).show(); return false }
        return true
    }

    private fun sendMessage() {
        val q = input.text.toString().trim(); if (q.isEmpty()) return
        if (!ready()) return
        val image = selectedImage
        selectedImage = null
        imagePreview.visibility = ImageView.GONE
        input.text.clear(); db.add("تو", q); append("\n\nتو: $q\n\nسایه: ..."); busy(true, "سایه در حال فکر کردن است...")
        val recent = db.recentMessages(60)
        val arr = JSONArray()
        for (m in recent) arr.put(JSONObject().put("role", if (m.first == "تو") "user" else "assistant").put("content", m.second))
        if (image != null) {
            val content = JSONArray()
                .put(JSONObject().put("type", "input_text").put("text", q))
                .put(JSONObject().put("type", "input_image").put("image_url", bitmapDataUrl(image)).put("detail", "high"))
            val last = JSONObject().put("role", "user").put("content", content)
            arr.put(last)
        }
        val bodyJson = JSONObject().put("model", "gpt-5.6-luna").put("instructions", systemPrompt).put("input", arr)
        postResponses(bodyJson, { answer -> db.add("سایه", answer); append("\n\nسایه: $answer"); busy(false, "حافظه فعال • متصل") }, { err -> append("\n\nخطا: $err"); busy(false, "خطا") })
    }

    private fun postResponses(json: JSONObject, ok: (String) -> Unit, fail: (String) -> Unit) {
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url("https://api.openai.com/v1/responses").addHeader("Authorization", "Bearer ${apiKey()}").addHeader("Content-Type", "application/json").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = runOnUiThread { fail(e.message ?: "اتصال برقرار نشد") }
            override fun onResponse(call: Call, response: Response) {
                response.use { val raw = it.body?.string().orEmpty(); if (!it.isSuccessful) runOnUiThread { fail("API ${it.code}: ${shortError(raw)}") } else runOnUiThread { ok(extractText(raw)) } }
            }
        })
    }

    private fun generateImage() {
        val prompt = input.text.toString().trim(); if (prompt.isEmpty()) { Toast.makeText(this, "اول توضیح تصویر را بنویس.", Toast.LENGTH_SHORT).show(); return }
        if (!ready()) return
        input.text.clear(); busy(true, "سایه در حال ساخت تصویر است...")
        val tools = JSONArray().put(JSONObject().put("type", "image_generation").put("model", "gpt-image-2").put("size", "1024x1024").put("quality", "auto"))
        val body = JSONObject().put("model", "gpt-5.6-luna").put("instructions", "Generate the requested image. Return the generated image using the image generation tool.").put("input", prompt).put("tools", tools)
        val req = Request.Builder().url("https://api.openai.com/v1/responses").addHeader("Authorization", "Bearer ${apiKey()}").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = runOnUiThread { busy(false, "خطا"); Toast.makeText(this@MainActivity, e.message ?: "اتصال ناموفق", Toast.LENGTH_LONG).show() }
            override fun onResponse(call: Call, response: Response) { response.use { val raw = it.body?.string().orEmpty(); if (!it.isSuccessful) runOnUiThread { busy(false, "خطا"); Toast.makeText(this@MainActivity, "API ${it.code}: ${shortError(raw)}", Toast.LENGTH_LONG).show() } else { val b64 = extractImageBase64(raw); runOnUiThread { busy(false, "تصویر آماده است"); if (b64 != null) showAndSaveImage(b64) else append("\n\nسایه: پاسخ تصویری دریافت شد اما داده تصویر پیدا نشد.") } } } }
        })
    }

    private fun pickImage() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, PICK_IMAGE) }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.openInputStream(uri)?.use { val bmp = BitmapFactory.decodeStream(it); selectedImage = bmp; imagePreview.setImageBitmap(bmp); imagePreview.visibility = ImageView.VISIBLE; status.text = "عکس انتخاب شد • حالا درباره‌اش بنویس" }
        }
    }

    private fun startSpeech() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { Toast.makeText(this, "تشخیص گفتار روی این گوشی در دسترس نیست.", Toast.LENGTH_LONG).show(); return }
        speech?.destroy(); speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "🎙 گوش می‌دهم..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status.text = "در حال تبدیل صدا..." }
            override fun onError(error: Int) { status.text = "آماده" }
            override fun onResults(results: Bundle?) { val s = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); if (s.isNotBlank()) input.append(if (input.text.isEmpty()) s else " $s"); status.text = "آماده" }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speech?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR"); putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) })
    }

    private fun speakLast() {
        val last = db.lastAssistant() ?: return
        tts.speak(last, TextToSpeech.QUEUE_FLUSH, null, "sayeh-last")
    }

    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts.language = Locale("fa", "IR") }

    private fun clearMemory() { db.clear(); chat.text = "سایه: حافظه گفتگو پاک شد.\n\nهر چیزی را از نو شروع کنیم؟"; imagePreview.visibility = ImageView.GONE; selectedImage = null }

    private fun showAndSaveImage(b64: String) {
        try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            imagePreview.setImageBitmap(bmp); imagePreview.visibility = ImageView.VISIBLE
            val values = android.content.ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "sayeh_${System.currentTimeMillis()}.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sayeh") }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            append("\n\nسایه: تصویر ساخته شد و در گالری، پوشه Sayeh ذخیره شد.")
        } catch (e: Exception) { append("\n\nخطا در نمایش تصویر: ${e.message}") }
    }

    private fun bitmapDataUrl(bitmap: Bitmap): String { val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out); return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP) }

    private fun extractText(raw: String): String { return try { val root = JSONObject(raw); val out = root.optJSONArray("output") ?: return "پاسخی دریافت نشد."; val sb = StringBuilder(); for (i in 0 until out.length()) { val item = out.optJSONObject(i) ?: continue; val c = item.optJSONArray("content") ?: continue; for (j in 0 until c.length()) { val p = c.optJSONObject(j) ?: continue; val t = p.optString("text"); if (t.isNotEmpty()) sb.append(t) } }; sb.toString().ifBlank { "پاسخی دریافت نشد." } } catch (_: Exception) { "پاسخ دریافت شد، اما خواندن آن با مشکل روبه‌رو شد." } }

    private fun extractImageBase64(raw: String): String? { return try { val root = JSONObject(raw); val out = root.optJSONArray("output") ?: return null; for (i in 0 until out.length()) { val item = out.optJSONObject(i) ?: continue; if (item.optString("type").contains("image_generation")) { val result = item.optString("result"); if (result.isNotBlank()) return result; val b64 = item.optString("b64_json"); if (b64.isNotBlank()) return b64 } }; null } catch (_: Exception) { null } }
    private fun shortError(raw: String): String = try { JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty().ifBlank { "خطای نامشخص" }.take(220) } catch (_: Exception) { "خطای نامشخص" }
    private fun append(s: String) { chat.append(s) }
    private fun busy(b: Boolean, msg: String) { runOnUiThread { send.isEnabled = !b; status.text = msg } }

    override fun onDestroy() { speech?.destroy(); tts.shutdown(); db.close(); super.onDestroy() }
}

class MemoryDb(context: Context) : android.database.sqlite.SQLiteOpenHelper(context, "sayeh_memory.db", null, 2) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) { db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL)") }
    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun add(role: String, text: String) { writableDatabase.insert("messages", null, android.content.ContentValues().apply { put("role", role); put("text", text); put("created", System.currentTimeMillis()) }) }
    fun allMessages(): List<Pair<String, String>> { val r = mutableListOf<Pair<String, String>>(); readableDatabase.rawQuery("SELECT role,text FROM messages ORDER BY id", null).use { while (it.moveToNext()) r.add(it.getString(0) to it.getString(1)) }; return r }
    fun recentMessages(limit: Int): List<Pair<String, String>> { val r = mutableListOf<Pair<String, String>>(); readableDatabase.rawQuery("SELECT role,text FROM messages ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { while (it.moveToNext()) r.add(it.getString(0) to it.getString(1)) }; r.reverse(); return r }
    fun lastAssistant(): String? { readableDatabase.rawQuery("SELECT text FROM messages WHERE role='سایه' ORDER BY id DESC LIMIT 1", null).use { return if (it.moveToFirst()) it.getString(0) else null } }
    fun clear() { writableDatabase.delete("messages", null, null) }
}
