package com.jsbeth.camslow

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/** XML 레이아웃 없이 코드로 화면을 짜기 위한 작은 도우미. 모든 요소는 현재 부모(root 또는 group 상자)에 차례로 붙는다. */
class Ui(private val act: Activity) {
    companion object {
        const val GREEN = 0xFF2E7D32.toInt()
        const val BLUE = 0xFF1565C0.toInt()
        const val ORANGE = 0xFFEF6C00.toInt()
        const val GREY = 0xFF616161.toInt()
        const val RED = 0xFFC62828.toInt()
        const val TEAL = 0xFF009688.toInt()
        const val MUTED = 0x99000000.toInt()
        private val CIRCLED = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨")
    }

    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(16)
        setPadding(p, p, p, p)
    }
    private var parent: LinearLayout = root

    fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    fun show() = act.setContentView(ScrollView(act).apply { addView(root) })

    fun text(s: String = "", size: Float = 15f, bold: Boolean = false, color: Int? = null): TextView =
        TextView(act).apply {
            text = s; textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (color != null) setTextColor(color)
            setPadding(0, dp(4), 0, dp(4))
        }.also { parent.addView(it) }

    fun section(title: String) = text(title, 18f, bold = true)
    fun question(n: Int, q: String) = text("${CIRCLED.getOrElse(n - 1) { "$n." }} $q", 16f, bold = true).apply { setPadding(0, dp(14), 0, dp(2)) }
    fun hint(s: String) = text(s, 12.5f, color = MUTED)

    /** 색 띠: 큰 글자 + 작은 설명. */
    fun band(): TextView = TextView(act).apply {
        textSize = 15f; setTextColor(Color.WHITE)
        val p = dp(12); setPadding(dp(14), p, dp(14), p)
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(GREY) }
    }.also { parent.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(8) }) }

    fun setBand(v: TextView, color: Int, big: String, sub: String) {
        (v.background as GradientDrawable).setColor(color)
        v.text = android.text.SpannableStringBuilder().apply {
            append(big); setSpan(android.text.style.RelativeSizeSpan(1.75f), 0, big.length, 0); setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, big.length, 0)
            if (sub.isNotEmpty()) append("\n").append(sub)
        }
    }

    fun banner(s: String): TextView = TextView(act).apply {
        text = s; textSize = 13f; setTextColor(0xFF7A3E00.toInt())
        val p = dp(8); setPadding(dp(12), p, dp(12), p)
        background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFFFFF3E0.toInt()) }
    }.also { parent.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }) }

    fun button(label: String, onClick: () -> Unit): Button =
        Button(act).apply { text = label; setOnClickListener { onClick() } }.also { parent.addView(it) }

    fun bigButton(label: String, color: Int, onClick: () -> Unit): Button =
        Button(act).apply {
            text = label; textSize = 18f; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(color)
            minHeight = dp(52)
            setOnClickListener { onClick() }
        }.also { parent.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) }) }

    fun setButton(b: Button, label: String, color: Int) { b.text = label; b.backgroundTintList = ColorStateList.valueOf(color) }

    fun row(vararg views: View): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            (v.parent as? LinearLayout)?.removeView(v)
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }.also { parent.addView(it) }

    /** 테두리 상자. 안에서 만드는 요소는 상자에 들어간다. */
    fun group(block: Ui.() -> Unit): LinearLayout {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(10); setPadding(dp(12), p, dp(12), p)
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setStroke(dp(1), 0x24000000); setColor(Color.TRANSPARENT) }
        }
        parent.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) })
        val prev = parent; parent = box
        try { block() } finally { parent = prev }
        return box
    }

    /** 접었다 펴는 묶음. 제목 줄을 누르면 토글. */
    fun collapsible(title: String, expanded: Boolean = false, block: Ui.() -> Unit): LinearLayout {
        val head = TextView(act).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, dp(6)) }
        parent.addView(head)
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = if (expanded) View.VISIBLE else View.GONE }
        parent.addView(body)
        fun label() { head.text = "$title   ${if (body.visibility == View.VISIBLE) "접기 ▴" else "펼치기 ▾"}" }
        label()
        head.setOnClickListener { body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE; label() }
        val prev = parent; parent = body
        try { block() } finally { parent = prev }
        return body
    }

    fun number(label: String, value: String, decimal: Boolean = true): EditText {
        text(label, 13f)
        return EditText(act).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or InputType.TYPE_NUMBER_FLAG_SIGNED
        }.also { parent.addView(it) }
    }

    /** 왼쪽에 이름·설명, 오른쪽에 숫자 칸. 유효한 숫자를 입력할 때마다 onChange. */
    fun numberRow(label: String, sub: String, value: String, decimal: Boolean, onChange: (Double) -> Unit): EditText {
        val texts = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply { text = label; textSize = 14f })
            if (sub.isNotEmpty()) addView(TextView(act).apply { text = sub; textSize = 12f; setTextColor(MUTED) })
        }
        val edit = EditText(act).apply {
            setText(value); textSize = 15f; gravity = Gravity.END
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or InputType.TYPE_NUMBER_FLAG_SIGNED
            minWidth = dp(72)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { s?.toString()?.toDoubleOrNull()?.let(onChange) }
            })
        }
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }.also { parent.addView(it) }
        return edit
    }

    fun check(label: String, value: Boolean): android.widget.CheckBox =
        android.widget.CheckBox(act).apply { text = label; isChecked = value }.also { parent.addView(it) }

    /** 스위치 한 줄: 이름(+설명) 왼쪽, 스위치 오른쪽. 바꾸면 onChange. */
    fun switchRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val texts = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply { text = label; textSize = 15f })
            if (sub.isNotEmpty()) addView(TextView(act).apply { text = sub; textSize = 12f; setTextColor(MUTED) })
        }
        val sw = Switch(act).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) } }
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
        }.also { parent.addView(it) }
        return sw
    }

    /** 라디오 묶음. 고르면 onSelect(index). */
    fun radioGroup(options: List<String>, selected: Int, onSelect: (Int) -> Unit): RadioGroup {
        val g = RadioGroup(act).apply { orientation = RadioGroup.VERTICAL }
        val ids = ArrayList<Int>()
        options.forEach { o ->
            val rb = RadioButton(act).apply { id = View.generateViewId(); text = o; textSize = 15f }
            ids += rb.id; g.addView(rb)
        }
        if (selected in ids.indices) g.check(ids[selected])
        g.setOnCheckedChangeListener { _, id -> ids.indexOf(id).takeIf { it >= 0 }?.let(onSelect) }
        parent.addView(g)
        return g
    }

    /** 목록에서 하나 고르기. 사용자가 바꿀 때만 onSelect. */
    fun choice(label: String, options: List<String>, selected: Int, onSelect: ((Int) -> Unit)? = null): Spinner {
        if (label.isNotEmpty()) text(label, 13f)
        return Spinner(act).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected.coerceIn(0, maxOf(0, options.size - 1)), false)
            if (onSelect != null) onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                var first = true
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (first) { first = false; return }; onSelect(pos) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }.also { parent.addView(it) }
    }

    fun multiline(label: String, value: String): EditText {
        text(label, 13f)
        return EditText(act).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }.also { parent.addView(it) }
    }
}
