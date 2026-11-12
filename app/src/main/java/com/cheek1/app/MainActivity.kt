package com.cheek1.app

import android.app.*
import android.os.Bundle
import android.content.*
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.concurrent.Executor

data class CheckItem(var text: String, var done: Boolean = false)
data class CheckList(var id: Long, var name: String, val items: MutableList<CheckItem> = mutableListOf(), var folderId: Long = 0L)
data class CheckFolder(var id: Long, var name: String, var color: Int = 0xFFE8F0FE.toInt())

class MainActivity : FragmentActivity() {
    private val lists = mutableListOf<CheckList>()
    private val folders = mutableListOf<CheckFolder>()
    private var currentFolderId: Long? = null
    private var selectedId: Long = -1
    private lateinit var content: LinearLayout
    private lateinit var listSpinner: Spinner
    private var fontSize = 16f
    private var customTypeface: Typeface? = null
    private val prefs by lazy { getSharedPreferences("cheek1_settings", MODE_PRIVATE) }

    companion object {
        private const val REQ_IMPORT_XLSX = 10
        private const val REQ_EXPORT_XLSX = 11
        private const val REQ_BACKUP_OUT = 12
        private const val REQ_BACKUP_IN = 13
        private const val REQ_FONT = 14
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fontSize = prefs.getFloat("font_size", 16f)
        loadData()
        if (lists.isEmpty()) {
            lists.add(CheckList(System.currentTimeMillis(), "چک‌لیست من"))
            selectedId = lists.first().id
            saveData()
        } else selectedId = lists.first().id
        buildUi()
        if (prefs.getBoolean("lock_enabled", false)) authenticate()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 24); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroll.addView(content); setContentView(scroll)
        content.addView(label("CHEEK1", 26f, true))
        content.addView(label("مدیریت آفلاین چک‌لیست‌ها", 14f, false))
        if (currentFolderId == null) renderHome() else renderFolder()
    }

    private fun renderHome() {
        content.addView(label("پوشه‌های من", 22f, true))
        content.addView(button("＋ ایجاد پوشه جدید") { editDialog("نام پوشه", "") { name -> if (name.isNotBlank()) { folders.add(CheckFolder(System.currentTimeMillis(), name.trim())); saveData(); buildUi() } } })
        folders.forEach { folder ->
            val card = TextView(this).apply {
                text = "📁  " + folder.name + "\n" + lists.count { it.folderId == folder.id } + " چک‌لیست"
                textSize = fontSize; typeface = customTypeface ?: Typeface.DEFAULT_BOLD
                setTextColor(0xFF263238.toInt()); setBackgroundColor(folder.color); setPadding(20, 22, 20, 22)
                gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setOnClickListener { currentFolderId = folder.id; buildUi() }
                setOnLongClickListener { showFolderMenu(folder); true }
            }
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
        }
        val unfiled = TextView(this).apply { text = "📂  بدون پوشه\n" + lists.count { it.folderId == 0L } + " چک‌لیست"; textSize = fontSize; setPadding(20,22,20,22); setBackgroundColor(0xFFF1F3F4.toInt()); layoutDirection = View.LAYOUT_DIRECTION_RTL; setOnClickListener { currentFolderId = 0L; buildUi() } }
        content.addView(unfiled, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
        content.addView(button("تنظیمات و پشتیبان‌گیری") { showSettings() })
    }

    private fun renderFolder() {
        val folder = folders.firstOrNull { it.id == currentFolderId }
        content.addView(button("← بازگشت به پوشه‌ها") { currentFolderId = null; buildUi() })
        content.addView(label(if (currentFolderId == 0L) "بدون پوشه" else folder?.name ?: "پوشه", 22f, true))
        content.addView(button("＋ ایجاد چک‌لیست") { createList() })
        content.addView(button("تنظیمات و پشتیبان‌گیری") { showSettings() })
        val selected = lists.filter { it.folderId == (currentFolderId ?: 0L) }
        selected.forEach { list -> content.addView(button("☷  " + list.name + "  •  " + list.items.size + " مورد") { selectedId = list.id; showChecklist() }) }
        if (selected.isEmpty()) content.addView(label("هنوز چک‌لیستی در این پوشه نیست.", fontSize, false))
    }

    private fun showFolderMenu(folder: CheckFolder) {
        AlertDialog.Builder(this).setTitle(folder.name).setItems(arrayOf("تغییر نام","تغییر رنگ","حذف پوشه")) { _, which ->
            when (which) {
                0 -> editDialog("نام پوشه", folder.name) { name -> if (name.isNotBlank()) { folder.name=name.trim(); saveData(); buildUi() } }
                1 -> { folder.color = if (folder.color == 0xFFE8F0FE.toInt()) 0xFFE9F5EA.toInt() else if (folder.color == 0xFFE9F5EA.toInt()) 0xFFFFF1DD.toInt() else 0xFFE8F0FE.toInt(); saveData(); buildUi() }
                2 -> AlertDialog.Builder(this).setMessage("چک‌لیست‌های این پوشه به بخش بدون پوشه منتقل شوند؟").setNegativeButton("انصراف", null).setPositiveButton("حذف") { _, _ -> lists.filter { it.folderId == folder.id }.forEach { it.folderId=0L }; folders.remove(folder); saveData(); currentFolderId=null; buildUi() }.show()
            }
        }.show()
    }

    private fun showChecklist() {
        val list = currentList() ?: return
        content.removeAllViews()
        content.addView(button("← بازگشت به پوشه") { currentFolderId=list.folderId; buildUi() })
        content.addView(label(list.name, 22f, true))
        content.addView(button("تغییر نام چک‌لیست") { renameList() })
        content.addView(button("حذف چک‌لیست") { deleteList() })
        content.addView(button("انتقال به پوشه") { moveList(list) })
        content.addView(button("افزودن مورد") { addItem() })
        content.addView(button("ورود اکسل؛ هر ردیف یک مورد") { chooseFile("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", REQ_IMPORT_XLSX) })
        content.addView(button("خروجی اکسل") { chooseCreate("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "checklist.xlsx", REQ_EXPORT_XLSX) })
        renderItems()
    }

    private fun moveList(list: CheckList) {
        val options = listOf("بدون پوشه") + folders.map { it.name }
        AlertDialog.Builder(this).setTitle("انتقال چک‌لیست به").setItems(options.toTypedArray()) { _, which -> list.folderId=if (which==0) 0L else folders[which-1].id; saveData(); currentFolderId=list.folderId; buildUi() }.show()
    }

    private fun showSettings() {
        content.removeAllViews()
        content.addView(button("← بازگشت") { currentFolderId=null; buildUi() })
        content.addView(label("تنظیمات و پشتیبان‌گیری",20f,true))
        content.addView(button("خروجی فایل پشتیبان") { chooseCreate("application/json","cheek1-backup.json",REQ_BACKUP_OUT) })
        content.addView(button("ورود فایل پشتیبان") { chooseFile("application/json",REQ_BACKUP_IN) })
        content.addView(button("تنظیم اندازه فونت") { changeFontSize() })
        content.addView(button("افزودن فونت دلخواه") { chooseFile("*/*",REQ_FONT) })
        content.addView(button(if (prefs.getBoolean("lock_enabled",false)) "غیرفعال‌کردن قفل اثر انگشت" else "فعال‌کردن قفل اثر انگشت") { toggleBiometric() })
    }
    private fun renderItems() {
        if (!::content.isInitialized) return
        // Rebuild the item area at the end, keeping the controls above it.
        val old = content.findViewWithTag<LinearLayout>("items_container")
        if (old != null) content.removeView(old)
        val area = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "items_container"
        }
        val list = currentList() ?: return
        if (list.items.isEmpty()) area.addView(label("هنوز موردی ثبت نشده است.", fontSize, false))
        list.items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val check = CheckBox(this).apply {
                text = item.text
                isChecked = item.done
                textSize = fontSize
                typeface = customTypeface ?: Typeface.DEFAULT
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setOnCheckedChangeListener { _, checked ->
                    item.done = checked
                    saveData()
                }
            }
            row.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(smallButton("ویرایش") { editItem(index) })
            row.addView(smallButton("حذف") {
                AlertDialog.Builder(this).setMessage("این مورد حذف شود؟")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("حذف") { _, _ -> list.items.removeAt(index); saveData(); renderItems() }.show()
            })
            area.addView(row)
            area.addView(divider())
        }
        content.addView(area)
    }

    private fun currentList() = lists.firstOrNull { it.id == selectedId }

    private fun refreshSpinner() {
        if (!::listSpinner.isInitialized) return
        listSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lists.map { it.name })
        val idx = lists.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        listSpinner.setSelection(idx)
    }

    private fun createList() {
        editDialog("نام چک‌لیست جدید", "") { name ->
            if (name.isNotBlank()) {
                val item = CheckList(System.currentTimeMillis(), name.trim(), folderId = currentFolderId ?: 0L)
                lists.add(item); selectedId = item.id; saveData(); showChecklist()
            }
        }
    }

    private fun renameList() {
        val list = currentList() ?: return
        editDialog("نام جدید", list.name) { value ->
            if (value.isNotBlank()) { list.name = value.trim(); saveData(); refreshSpinner() }
        }
    }

    private fun deleteList() {
        val list = currentList() ?: return
        AlertDialog.Builder(this).setMessage("چک‌لیست «${list.name}» حذف شود؟")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("حذف") { _, _ ->
                lists.remove(list)
                if (lists.isEmpty()) lists.add(CheckList(System.currentTimeMillis(), "چک‌لیست من"))
                selectedId = lists.first().id
                saveData(); refreshSpinner(); renderItems()
            }.show()
    }

    private fun addItem() {
        editDialog("متن مورد جدید", "") { value ->
            if (value.isNotBlank()) { currentList()?.items?.add(CheckItem(value.trim())); saveData(); renderItems() }
        }
    }

    private fun editItem(index: Int) {
        val list = currentList() ?: return
        if (index !in list.items.indices) return
        editDialog("ویرایش مورد", list.items[index].text) { value ->
            if (value.isNotBlank()) { list.items[index].text = value.trim(); saveData(); renderItems() }
        }
    }

    private fun editDialog(title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); textSize = fontSize; setPadding(24, 16, 24, 16) }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره") { _, _ -> done(input.text.toString()) }.show()
    }

    private fun changeFontSize() {
        val input = EditText(this).apply { inputType = 2; setText(fontSize.toInt().toString()) }
        AlertDialog.Builder(this).setTitle("اندازه فونت (۱۲ تا ۳۰)")
            .setView(input).setNegativeButton("انصراف", null)
            .setPositiveButton("ثبت") { _, _ ->
                fontSize = (input.text.toString().toFloatOrNull() ?: 16f).coerceIn(12f, 30f)
                prefs.edit().putFloat("font_size", fontSize).apply()
                buildUi()
            }.show()
    }

    private fun toggleBiometric() {
        val enabled = prefs.getBoolean("lock_enabled", false)
        if (enabled) {
            prefs.edit().putBoolean("lock_enabled", false).apply()
            Toast.makeText(this, "قفل غیرفعال شد", Toast.LENGTH_SHORT).show()
            buildUi()
            return
        }
        val manager = BiometricManager.from(this)
        val result = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        if (result != BiometricManager.BIOMETRIC_SUCCESS) {
            toast("قفل اثر انگشت یا رمز دستگاه روی این گوشی در دسترس نیست.")
            return
        }
        prefs.edit().putBoolean("lock_enabled", true).apply()
        toast("قفل فعال شد؛ از اجرای بعدی برنامه اعمال می‌شود.")
        buildUi()
    }

    private fun authenticate() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) finish()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("باز کردن CHEEK1")
            .setSubtitle("تأیید هویت برای دسترسی به چک‌لیست‌ها")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }

    private fun chooseFile(mime: String, request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime
        }
        startActivityForResult(intent, request)
    }

    private fun chooseCreate(mime: String, name: String, request: Int) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, request)
    }

    @Deprecated("Legacy picker callback retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                REQ_IMPORT_XLSX -> importExcel(uri)
                REQ_EXPORT_XLSX -> exportExcel(uri)
                REQ_BACKUP_OUT -> writeBackup(uri)
                REQ_BACKUP_IN -> readBackup(uri)
                REQ_FONT -> importFont(uri)
            }
        } catch (e: Exception) {
            toast("عملیات انجام نشد: ${e.message ?: "خطای نامشخص"}")
        }
    }


    private fun importExcel(uri: Uri) {
        val list = currentList() ?: return
        val rows = SimpleXlsx.readFirstColumn(contentResolver.openInputStream(uri) ?: error("فایل خوانده نشد"))
        var count = 0
        rows.forEach { value ->
            if (value.isNotBlank()) { list.items.add(CheckItem(value.trim())); count++ }
        }
        saveData(); renderItems()
        toast("$count مورد از اکسل وارد شد.")
    }

    private fun exportExcel(uri: Uri) {
        val list = currentList() ?: return
        val rows = mutableListOf(listOf("مورد", "انجام شده"))
        list.items.forEach { rows.add(listOf(it.text, if (it.done) "بله" else "خیر")) }
        contentResolver.openOutputStream(uri).use { output ->
            SimpleXlsx.write(output ?: error("فایل خروجی باز نشد"), rows)
        }
        toast("فایل اکسل ذخیره شد.")
    }

    private fun writeBackup(uri: Uri) {
        val json = JSONObject().put("format", "CHEEK1_BACKUP").put("version", 2)
        val arr = JSONArray()
        lists.forEach { list ->
            val items = JSONArray()
            list.items.forEach { items.put(JSONObject().put("text", it.text).put("done", it.done)) }
            arr.put(JSONObject().put("id", list.id).put("name", list.name).put("items", items))
        }
        json.put("lists", arr)
        contentResolver.openOutputStream(uri).use { it?.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
        toast("فایل پشتیبان ذخیره شد.")
    }

    private fun readBackup(uri: Uri) {
        val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("فایل خوانده نشد")
        val json = JSONObject(raw)
        val arr = json.optJSONArray("lists") ?: json.optJSONArray("checklists") ?: JSONArray()
        val restored = mutableListOf<CheckList>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", obj.optString("title", "چک‌لیست ${i + 1}"))
            val id = obj.optLong("id", System.currentTimeMillis() + i)
            val itemArray = obj.optJSONArray("items") ?: obj.optJSONArray("tasks") ?: JSONArray()
            val items = mutableListOf<CheckItem>()
            for (j in 0 until itemArray.length()) {
                val entry = itemArray.opt(j)
                when (entry) {
                    is JSONObject -> items.add(CheckItem(entry.optString("text", entry.optString("title", "")), entry.optBoolean("done", entry.optBoolean("completed", false))))
                    else -> if (entry != null && entry.toString().isNotBlank()) items.add(CheckItem(entry.toString()))
                }
            }
            restored.add(CheckList(id, name, items))
        }
        if (restored.isEmpty()) error("چک‌لیستی در فایل پشتیبان پیدا نشد")
        lists.clear(); lists.addAll(restored); selectedId = lists.first().id
        saveData(); buildUi(); toast("بازیابی با موفقیت انجام شد.")
    }

    private fun importFont(uri: Uri) {
        val name = "custom-font-${System.currentTimeMillis()}.ttf"
        val target = File(filesDir, name)
        contentResolver.openInputStream(uri).use { input ->
            target.outputStream().use { output -> input?.copyTo(output) }
        }
        customTypeface = Typeface.createFromFile(target)
        prefs.edit().putString("font_file", name).apply()
        buildUi()
        toast("فونت دلخواه بارگذاری شد.")
    }

    private fun loadData() {
        val file = File(filesDir, "checklists.json")
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val items = mutableListOf<CheckItem>()
                val itemArr = obj.optJSONArray("items") ?: JSONArray()
                for (j in 0 until itemArr.length()) {
                    val it = itemArr.getJSONObject(j)
                    items.add(CheckItem(it.optString("text"), it.optBoolean("done")))
                }
                lists.add(CheckList(obj.optLong("id"), obj.optString("name"), items))
            }
        } catch (_: Exception) { lists.clear() }
        val font = prefs.getString("font_file", null)
        if (font != null) try { customTypeface = Typeface.createFromFile(File(filesDir, font)) } catch (_: Exception) {}
    }

    private fun saveData() {
        val arr = JSONArray()
        lists.forEach { list ->
            val items = JSONArray()
            list.items.forEach { items.put(JSONObject().put("text", it.text).put("done", it.done)) }
            arr.put(JSONObject().put("id", list.id).put("name", list.name).put("items", items))
        }
        File(filesDir, "checklists.json").writeText(arr.toString())
    }

    private fun label(text: String, size: Float, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; gravity = Gravity.CENTER
        setPadding(8, 12, 8, 12); typeface = if (bold) Typeface.DEFAULT_BOLD else customTypeface ?: Typeface.DEFAULT
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; textSize = 14f; setOnClickListener { action() } }
    private fun smallButton(text: String, action: () -> Unit) = Button(this).apply { this.text = text; textSize = 10f; minWidth = 0; setPadding(4, 0, 4, 0); setOnClickListener { action() } }
    private fun divider() = View(this).apply { setBackgroundColor(0xFFE0E0E0.toInt()); layoutParams = LinearLayout.LayoutParams(-1, 1) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
