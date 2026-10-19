package com.ozal.debtmanager

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("accounts", MODE_PRIVATE) }
    private val people = mutableListOf<Person>()
    private val transactions = mutableListOf<Tx>()
    private lateinit var root: LinearLayout
    private lateinit var summary: TextView
    private lateinit var rows: LinearLayout
    private var filter = "همه"
    private var searchText = ""
    private var currentPersonId: Long? = null
    private var pendingDueDate: Long = 0L
    private var pendingTxDate: Long = System.currentTimeMillis()

    data class Person(val id: Long, var name: String, var kind: String, var note: String, var dueDate: Long)
    data class Tx(val id: Long, val personId: Long, val amount: Long, val action: String, val date: Long, val note: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(20, 76, 67)
        window.navigationBarColor = Color.rgb(20, 76, 67)
        load()
        renderHome()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun money(n: Long) = NumberFormat.getNumberInstance(Locale("fa", "IR")).format(n)
    private fun date(ms: Long): String = if (ms <= 0L) "تعیین نشده" else SimpleDateFormat("yyyy/MM/dd", Locale("fa", "IR")).format(Date(ms))
    private fun text(s: String, size: Float = 16f, color: Int = Color.rgb(34, 48, 44)): TextView =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(5), dp(6), dp(5), dp(6))
        }
    private fun btn(s: String, action: () -> Unit): Button = Button(this).apply {
        text = s; isAllCaps = false; setOnClickListener { action() }
    }
    private fun input(hintText: String, numeric: Boolean = false): EditText = EditText(this).apply {
        hint = hintText
        textDirection = View.TEXT_DIRECTION_RTL
        if (numeric) inputType = 2
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }
    private fun page(title: String) {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(Color.rgb(247, 249, 248))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(text(title, 25f, Color.rgb(20, 76, 67)))
    }

    private fun renderHome() {
        currentPersonId = null
        page("مدیریت بدهی و طلب")
        root.addView(text("دفتر حساب شخصی • ذخیره روی همین گوشی • بدون نیاز به اینترنت", 13f, Color.DKGRAY))
        summary = text("", 16f, Color.WHITE).apply {
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setBackgroundColor(Color.rgb(20, 76, 67))
        }
        root.addView(summary)
        root.addView(btn("＋ افزودن شخص / ثبت مبلغ اولیه") { addPerson() })
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(btn("پشتیبان‌گیری") { exportBackup() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        tools.addView(btn("بازیابی") { importBackup() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        tools.addView(btn("خروجی CSV") { exportCsv() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(tools)
        val search = input("جست‌وجوی نام شخص")
        search.setSingleLine(true)
        search.setText(searchText)
        root.addView(search)
        search.addTextChangedListener(SimpleWatcher { value -> searchText = value; refreshRows() })
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("همه", "طلب من", "بدهی من", "سررسید گذشته").forEach { f ->
            filterRow.addView(btn(f) { filter = f; refreshRows() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(filterRow)
        root.addView(text("پرونده اشخاص", 20f, Color.rgb(20, 76, 67)))
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rows)
        refreshRows()
    }

    private fun balance(p: Person): Long {
        var total = 0L
        transactions.filter { it.personId == p.id }.forEach { t ->
            val increase = (p.kind == "بدهی من" && t.action == "قرض گرفتم") ||
                (p.kind == "طلب من" && t.action == "قرض دادم")
            val decrease = (p.kind == "بدهی من" && t.action == "پس دادم") ||
                (p.kind == "طلب من" && t.action == "پس گرفتم")
            if (increase) total += t.amount
            if (decrease) total -= t.amount
        }
        return total
    }

    private fun refreshRows() {
        if (!::rows.isInitialized || !::summary.isInitialized) return
        val openDebt = people.filter { it.kind == "بدهی من" }.sumOf { balance(it).coerceAtLeast(0L) }
        val openReceivable = people.filter { it.kind == "طلب من" }.sumOf { balance(it).coerceAtLeast(0L) }
        summary.text = "طلب از دیگران: " + money(openReceivable) + " تومان\nبدهی من به دیگران: " + money(openDebt) +
            " تومان\nمانده خالص: " + money(openReceivable - openDebt) + " تومان\nتعداد پرونده‌ها: " + people.size
        rows.removeAllViews()
        val today = dayStart(System.currentTimeMillis())
        val visible = people.filter { p ->
            val b = balance(p)
            val matchesFilter = when (filter) {
                "طلب من" -> p.kind == "طلب من" && b > 0L
                "بدهی من" -> p.kind == "بدهی من" && b > 0L
                "سررسید گذشته" -> p.dueDate > 0L && p.dueDate < today && b > 0L
                else -> true
            }
            matchesFilter && (searchText.isBlank() || p.name.contains(searchText.trim(), true))
        }.sortedByDescending { it.id }
        if (visible.isEmpty()) {
            rows.addView(text("موردی پیدا نشد. با دکمه بالا اولین پرونده را ثبت کن.", 14f, Color.GRAY))
            return
        }
        visible.forEach { p ->
            val b = balance(p)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) }
            }
            card.addView(text(p.name, 18f, Color.rgb(20, 76, 67)))
            card.addView(text((if (p.kind == "بدهی من") "من به این شخص بدهکارم" else "این شخص به من بدهکار است") +
                "\nمانده: " + money(b) + " تومان", 15f))
            if (p.dueDate > 0L) {
                val overdue = p.dueDate < today && b > 0L
                card.addView(text("سررسید: " + date(p.dueDate) + if (overdue) " • سررسید گذشته" else "",
                    13f, if (overdue) Color.rgb(180, 50, 40) else Color.DKGRAY))
            }
            if (p.note.isNotBlank()) card.addView(text(p.note, 13f, Color.DKGRAY))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(btn("ریزتراکنش‌ها") { renderPerson(p.id) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(btn("ثبت پرداخت") { addTransaction(p.id) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(btn("اشتراک") { shareStatement(p.id) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            card.addView(actions)
            val more = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            more.addView(btn("تغییر سررسید") { editDueDate(p.id) }, LinearLayout.LayoutParams(0, dp(44), 1f))
            more.addView(btn("حذف پرونده") { deletePerson(p.id) }, LinearLayout.LayoutParams(0, dp(44), 1f))
            card.addView(more)
            rows.addView(card)
        }
    }

    private fun renderPerson(personId: Long) {
        currentPersonId = personId
        val p = people.firstOrNull { it.id == personId } ?: run { renderHome(); return }
        page("پرونده: " + p.name)
        root.addView(btn("← بازگشت به فهرست") { renderHome() })
        root.addView(text("نوع حساب: " + p.kind, 15f, Color.DKGRAY))
        root.addView(text("مانده فعلی: " + money(balance(p)) + " تومان", 22f, Color.rgb(20, 76, 67)))
        root.addView(text("سررسید: " + date(p.dueDate), 14f))
        if (p.note.isNotBlank()) root.addView(text("یادداشت: " + p.note, 14f))
        root.addView(btn("＋ ثبت تراکنش جدید (قرض / پرداخت)") { addTransaction(personId) })
        root.addView(btn("اشتراک ریزحساب با واتساپ و سایر برنامه‌ها") { shareStatement(personId) })
        root.addView(text("تاریخچه کامل تراکنش‌ها", 20f, Color.rgb(20, 76, 67)))
        val list = transactions.filter { it.personId == personId }.sortedByDescending { it.date }
        if (list.isEmpty()) root.addView(text("هنوز تراکنشی ثبت نشده است.", 14f, Color.GRAY))
        list.forEach { t ->
            val increase = (p.kind == "بدهی من" && t.action == "قرض گرفتم") ||
                (p.kind == "طلب من" && t.action == "قرض دادم")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
            }
            card.addView(text(t.action + " • " + date(t.date), 16f, if (increase) Color.rgb(20, 120, 80) else Color.rgb(180, 65, 50)))
            card.addView(text(money(t.amount) + " تومان", 18f))
            if (t.note.isNotBlank()) card.addView(text(t.note, 13f, Color.DKGRAY))
            card.addView(btn("حذف این تراکنش") {
                AlertDialog.Builder(this).setMessage("این تراکنش حذف شود؟ مانده حساب دوباره محاسبه می‌شود.")
                    .setNegativeButton("انصراف", null).setPositiveButton("حذف") { _, _ ->
                        transactions.removeAll { it.id == t.id }; save(); renderPerson(personId)
                    }.show()
            })
            root.addView(card)
        }
    }

    private fun addPerson() {
        pendingDueDate = 0L
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val name = input("نام شخص")
        val amount = input("مبلغ اولیه به تومان", true)
        val kind = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("بدهی من", "طلب من"))
        }
        val note = input("یادداشت (اختیاری)")
        val due = TextView(this).apply { text = "سررسید: تعیین نشده"; textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        panel.addView(name); panel.addView(amount); panel.addView(kind); panel.addView(note)
        panel.addView(btn("انتخاب تاریخ سررسید") { chooseDate(0L) { pendingDueDate = it; due.text = "سررسید: " + date(it) } })
        panel.addView(due)
        val dialog = AlertDialog.Builder(this).setTitle("پرونده جدید").setView(panel)
            .setNegativeButton("انصراف", null).setPositiveButton("ثبت", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val a = amount.text.toString().trim().replace(",", "").replace("٬", "").toLongOrNull()
                if (n.isBlank() || a == null || a <= 0L) {
                    Toast.makeText(this, "نام و مبلغ معتبر وارد کن.", Toast.LENGTH_LONG).show()
                } else {
                    val id = System.currentTimeMillis()
                    val k = kind.selectedItem.toString()
                    people.add(Person(id, n, k, note.text.toString().trim(), pendingDueDate))
                    transactions.add(Tx(id + 1L, id, a, if (k == "بدهی من") "قرض گرفتم" else "قرض دادم", System.currentTimeMillis(), "مبلغ اولیه"))
                    save(); dialog.dismiss(); renderHome()
                }
            }
        }
        dialog.show()
    }

    private fun addTransaction(personId: Long) {
        val p = people.firstOrNull { it.id == personId } ?: return
        pendingTxDate = System.currentTimeMillis()
        val actions = if (p.kind == "بدهی من") listOf("قرض گرفتم", "پس دادم") else listOf("قرض دادم", "پس گرفتم")
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val amount = input("مبلغ تراکنش به تومان", true)
        val action = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, actions)
        }
        val note = input("توضیح (مثلاً قسط اول)")
        val dateLabel = TextView(this).apply { text = "تاریخ: " + date(pendingTxDate); setPadding(0, dp(8), 0, dp(8)) }
        panel.addView(text("شخص: " + p.name, 15f))
        panel.addView(amount); panel.addView(action); panel.addView(note)
        panel.addView(btn("انتخاب تاریخ تراکنش") { chooseDate(pendingTxDate) { pendingTxDate = it; dateLabel.text = "تاریخ: " + date(it) } })
        panel.addView(dateLabel)
        val dialog = AlertDialog.Builder(this).setTitle("ثبت ریزتراکنش").setView(panel)
            .setNegativeButton("انصراف", null).setPositiveButton("ثبت", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = amount.text.toString().trim().replace(",", "").replace("٬", "").toLongOrNull()
                if (a == null || a <= 0L) {
                    Toast.makeText(this, "مبلغ معتبر وارد کن.", Toast.LENGTH_LONG).show()
                } else {
                    transactions.add(Tx(System.currentTimeMillis(), personId, a, action.selectedItem.toString(),
                        pendingTxDate, note.text.toString().trim()))
                    save(); dialog.dismiss()
                    if (currentPersonId == personId) renderPerson(personId) else renderHome()
                }
            }
        }
        dialog.show()
    }

    private fun chooseDate(initial: Long, done: (Long) -> Unit) {
        val c = Calendar.getInstance()
        if (initial > 0L) c.timeInMillis = initial
        DatePickerDialog(this, { _, y, m, d ->
            val chosen = Calendar.getInstance().apply {
                set(Calendar.YEAR, y); set(Calendar.MONTH, m); set(Calendar.DAY_OF_MONTH, d)
                set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis
            done(chosen)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun editDueDate(personId: Long) {
        val p = people.firstOrNull { it.id == personId } ?: return
        chooseDate(p.dueDate.takeIf { it > 0L } ?: System.currentTimeMillis()) {
            p.dueDate = it; save(); renderHome()
        }
    }
    private fun deletePerson(personId: Long) {
        AlertDialog.Builder(this).setMessage("پرونده و تمام ریزتراکنش‌های این شخص حذف شوند؟")
            .setNegativeButton("انصراف", null).setPositiveButton("حذف") { _, _ ->
                people.removeAll { it.id == personId }
                transactions.removeAll { it.personId == personId }
                save(); renderHome()
            }.show()
    }

    private fun shareStatement(personId: Long) {
        val p = people.firstOrNull { it.id == personId } ?: return
        val sb = StringBuilder()
        sb.append("ریزحساب ").append(p.name).append("\nنوع حساب: ").append(p.kind)
            .append("\nمانده فعلی: ").append(money(balance(p))).append(" تومان\n")
        sb.append("سررسید: ").append(date(p.dueDate)).append("\n\nتراکنش‌ها:\n")
        transactions.filter { it.personId == personId }.sortedBy { it.date }.forEach { t ->
            sb.append(date(t.date)).append(" | ").append(t.action).append(" | ")
                .append(money(t.amount)).append(" تومان")
            if (t.note.isNotBlank()) sb.append(" | ").append(t.note)
            sb.append("\n")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "ریزحساب " + p.name)
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, "ارسال ریزحساب"))
    }

    private fun exportCsv() {
        val sb = StringBuilder("نام شخص,نوع حساب,مانده,تاریخ,تراکنش,مبلغ,توضیحات\n")
        transactions.forEach { t ->
            val p = people.firstOrNull { it.id == t.personId } ?: return@forEach
            fun q(s: String) = "\"" + s.replace("\"", "\"\"").replace(",", "،").replace("\n", " ") + "\""
            sb.append(q(p.name)).append(",").append(q(p.kind)).append(",").append(balance(p)).append(",")
                .append(q(date(t.date))).append(",").append(q(t.action)).append(",").append(t.amount).append(",")
                .append(q(t.note)).append("\n")
        }
        shareTextFile("ریزتراکنش‌ها.csv", "text/csv", sb.toString())
    }
    private fun shareTextFile(name: String, mime: String, content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_SUBJECT, name); putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, "خروجی گرفتن"))
    }

    private fun exportBackup() {
        val obj = JSONObject()
        val ps = JSONArray()
        people.forEach { p -> ps.put(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("kind", p.kind); put("note", p.note); put("dueDate", p.dueDate)
        }) }
        val ts = JSONArray()
        transactions.forEach { t -> ts.put(JSONObject().apply {
            put("id", t.id); put("personId", t.personId); put("amount", t.amount)
            put("action", t.action); put("date", t.date); put("note", t.note)
        }) }
        obj.put("version", 2); obj.put("people", ps); obj.put("transactions", ts)
        shareTextFile("backup-debt-manager.json", "application/json", obj.toString(2))
    }
    private fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json" }
        startActivityForResult(i, 204)
    }
    @Deprecated("Deprecated in Android API but supported for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 204 && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val obj = JSONObject(content)
                val ps = obj.getJSONArray("people")
                val ts = obj.getJSONArray("transactions")
                val newPeople = mutableListOf<Person>()
                val newTx = mutableListOf<Tx>()
                for (i in 0 until ps.length()) {
                    val o = ps.getJSONObject(i)
                    newPeople.add(Person(o.getLong("id"), o.getString("name"), o.getString("kind"), o.optString("note"), o.optLong("dueDate")))
                }
                for (i in 0 until ts.length()) {
                    val o = ts.getJSONObject(i)
                    newTx.add(Tx(o.getLong("id"), o.getLong("personId"), o.getLong("amount"), o.getString("action"), o.optLong("date"), o.optString("note")))
                }
                AlertDialog.Builder(this).setMessage("بازیابی، اطلاعات فعلی را با نسخه پشتیبان جایگزین می‌کند. ادامه می‌دهی؟")
                    .setNegativeButton("انصراف", null).setPositiveButton("بازیابی") { _, _ ->
                        people.clear(); people.addAll(newPeople)
                        transactions.clear(); transactions.addAll(newTx)
                        save(); renderHome()
                    }.show()
            } catch (_: Exception) {
                Toast.makeText(this, "فایل پشتیبان معتبر نیست.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun save() {
        val ps = JSONArray()
        people.forEach { p -> ps.put(JSONObject().apply {
            put("id", p.id); put("name", p.name); put("kind", p.kind); put("note", p.note); put("dueDate", p.dueDate)
        }) }
        val ts = JSONArray()
        transactions.forEach { t -> ts.put(JSONObject().apply {
            put("id", t.id); put("personId", t.personId); put("amount", t.amount)
            put("action", t.action); put("date", t.date); put("note", t.note)
        }) }
        prefs.edit().putString("people_v2", ps.toString()).putString("transactions_v2", ts.toString()).apply()
    }

    private fun load() {
        try {
            val ps = JSONArray(prefs.getString("people_v2", "[]"))
            val ts = JSONArray(prefs.getString("transactions_v2", "[]"))
            for (i in 0 until ps.length()) {
                val o = ps.getJSONObject(i)
                people.add(Person(o.optLong("id"), o.optString("name"), o.optString("kind"),
                    o.optString("note"), o.optLong("dueDate")))
            }
            for (i in 0 until ts.length()) {
                val o = ts.getJSONObject(i)
                transactions.add(Tx(o.optLong("id"), o.optLong("personId"), o.optLong("amount"),
                    o.optString("action"), o.optLong("date"), o.optString("note")))
            }
            if (people.isEmpty() && transactions.isEmpty()) migrateLegacy()
        } catch (_: Exception) { migrateLegacy() }
    }

    private fun migrateLegacy() {
        try {
            val old = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                val id = o.optLong("id", System.currentTimeMillis() + i)
                val kind = o.optString("type", "بدهی من")
                val amount = o.optLong("amount")
                people.add(Person(id, o.optString("name"), kind, o.optString("note"), 0L))
                if (amount > 0L) transactions.add(Tx(id + 1L, id, amount,
                    if (kind == "بدهی من") "قرض گرفتم" else "قرض دادم", id, "انتقال از نسخه قبلی"))
                if (o.optBoolean("paid")) {
                    transactions.add(Tx(id + 2L, id, amount,
                        if (kind == "بدهی من") "پس دادم" else "پس گرفتم", id + 3L, "وضعیت تسویه قبلی"))
                }
            }
            if (people.isNotEmpty()) save()
        } catch (_: Exception) { }
    }

    private class SimpleWatcher(val changed: (String) -> Unit) : android.text.TextWatcher {
        private var value = ""
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { value = s?.toString() ?: "" }
        override fun afterTextChanged(s: android.text.Editable?) { changed(value) }
    }
}
