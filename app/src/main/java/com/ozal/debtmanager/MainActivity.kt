package com.ozal.debtmanager

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("accounts", MODE_PRIVATE) }
    private val items = mutableListOf<Item>()
    private lateinit var root: LinearLayout
    private lateinit var summary: TextView
    private lateinit var rows: LinearLayout
    private var filter = "همه"

    data class Item(val id: Long, val name: String, val amount: Long, val type: String, val note: String, var paid: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 79, 68)
        load()
        render()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

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

    private fun render() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(Color.rgb(247, 249, 248))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(text("مدیریت بدهی و طلب", 25f, Color.rgb(17,79,68)))
        root.addView(text("ثبت حساب‌های شخصی؛ اطلاعات روی همین گوشی ذخیره می‌شود.", 13f, Color.DKGRAY))
        summary = text("", 17f, Color.WHITE)
        summary.setPadding(dp(12), dp(14), dp(12), dp(14))
        summary.setBackgroundColor(Color.rgb(17,79,68))
        root.addView(summary)
        root.addView(btn("＋ ثبت بدهی یا طلب") { addItem() })
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("همه", "طلب من", "بدهی من", "تسویه‌شده").forEach { f ->
            filterRow.addView(btn(f) { filter = f; render() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(filterRow)
        root.addView(text("حساب‌ها", 20f, Color.rgb(17,79,68)))
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rows)
        refreshRows()
    }

    private fun money(n: Long) = NumberFormat.getNumberInstance(Locale("fa", "IR")).format(n)

    private fun refreshRows() {
        val open = items.filter { !it.paid }
        val rec = open.filter { it.type == "طلب من" }.sumOf { it.amount }
        val due = open.filter { it.type == "بدهی من" }.sumOf { it.amount }
        summary.text = "طلب وصول‌نشده: ${money(rec)} تومان\nبدهی پرداخت‌نشده: ${money(due)} تومان\nمانده خالص: ${money(rec-due)} تومان"
        rows.removeAllViews()
        val visible = items.filter {
            when (filter) {
                "طلب من" -> it.type == "طلب من" && !it.paid
                "بدهی من" -> it.type == "بدهی من" && !it.paid
                "تسویه‌شده" -> it.paid
                else -> true
            }
        }.asReversed()
        if (visible.isEmpty()) { rows.addView(text("موردی ثبت نشده است.", 14f, Color.GRAY)); return }
        visible.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) }
            }
            card.addView(text("${item.name} • ${item.type}", 17f, Color.rgb(17,79,68)))
            card.addView(text("${money(item.amount)} تومان", 16f))
            if (item.note.isNotBlank()) card.addView(text(item.note, 13f, Color.DKGRAY))
            card.addView(text(if (item.paid) "وضعیت: تسویه‌شده" else "وضعیت: باز", 13f))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(btn(if (item.paid) "بازگردانی" else "تسویه") {
                item.paid = !item.paid; save(); render()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(btn("حذف") {
                AlertDialog.Builder(this@MainActivity).setMessage("این حساب حذف شود؟")
                    .setNegativeButton("انصراف", null).setPositiveButton("حذف") { _, _ ->
                        items.removeAll { it.id == item.id }; save(); render()
                    }.show()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            card.addView(actions)
            rows.addView(card)
        }
    }

    private fun addItem() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val name = EditText(this).apply { hint = "نام شخص"; textDirection = View.TEXT_DIRECTION_RTL }
        val amount = EditText(this).apply { hint = "مبلغ به تومان"; inputType = 2 }
        val type = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("طلب من", "بدهی من")) }
        val note = EditText(this).apply { hint = "توضیحات (اختیاری)"; textDirection = View.TEXT_DIRECTION_RTL }
        panel.addView(name); panel.addView(amount); panel.addView(type); panel.addView(note)
        val dialog = AlertDialog.Builder(this).setTitle("ثبت حساب جدید").setView(panel)
            .setNegativeButton("انصراف", null).setPositiveButton("ثبت", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val a = amount.text.toString().trim().replace(",", "").toLongOrNull()
                if (n.isBlank() || a == null || a <= 0) {
                    Toast.makeText(this, "نام و مبلغ معتبر وارد کن.", Toast.LENGTH_LONG).show()
                } else {
                    items.add(Item(System.currentTimeMillis(), n, a, type.selectedItem.toString(), note.text.toString().trim(), false))
                    save(); dialog.dismiss(); render()
                }
            }
        }
        dialog.show()
    }

    private fun save() {
        val a = JSONArray()
        items.forEach { x -> a.put(JSONObject().apply {
            put("id", x.id); put("name", x.name); put("amount", x.amount)
            put("type", x.type); put("note", x.note); put("paid", x.paid)
        }) }
        prefs.edit().putString("items", a.toString()).apply()
    }

    private fun load() {
        try {
            val a = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                items.add(Item(o.optLong("id"), o.optString("name"), o.optLong("amount"), o.optString("type"), o.optString("note"), o.optBoolean("paid")))
            }
        } catch (_: Exception) { }
    }
}