package com.jsbeth.camslow

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

    // ---- v0.6 메인 화면 부품

    /** 흰 상자 테두리(계기판 칸). */
    private fun cardBg() = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.WHITE); setStroke(dp(1), 0x1F000000) }

    /** 계기 두 칸 나란히: 왼쪽 큰 숫자(속도), 오른쪽 표지판(제한속도). (속도 라벨, 속도 숫자, 제한 라벨, 표지판) */
    fun gauges(): Gauges {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            val p = dp(10); setPadding(p, p, p, p); background = cardBg()
        }
        val left = card(); val right = card()
        val speedLab = TextView(act).apply { textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER }
        val speed = TextView(act).apply { textSize = 46f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; includeFontPadding = false }
        val limitLab = TextView(act).apply { textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER }
        val sign = TextView(act).apply {
            textSize = 26f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(6), RED) }
        }
        left.addView(speedLab); left.addView(speed, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        right.addView(limitLab); right.addView(sign, LinearLayout.LayoutParams(dp(76), dp(76)).apply { topMargin = dp(6) })
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(5) })
        row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(5) })
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2); bottomMargin = dp(8) })
        return Gauges(speedLab, speed, limitLab, sign)
    }

    class Gauges(val speedLabel: TextView, val speed: TextView, val limitLabel: TextView, val sign: TextView)

    /** 표지판에 제한속도를 넣는다. null이면 회색 "없음". */
    fun setSign(g: Gauges, limit: Int?) {
        val bg = g.sign.background as GradientDrawable
        if (limit == null) { g.sign.text = "없음"; g.sign.textSize = 16f; g.sign.setTextColor(0xFF9E9E9E.toInt()); bg.setStroke(dp(6), 0xFFBDBDBD.toInt()) }
        else { g.sign.text = limit.toString(); g.sign.textSize = if (limit >= 100) 24f else 26f; g.sign.setTextColor(Color.BLACK); bg.setStroke(dp(6), RED) }
    }

    /** 속도 숫자 + 작은 단위. */
    fun setSpeed(g: Gauges, kmh: Int?, color: Int) {
        g.speed.setTextColor(color)
        g.speed.text = if (kmh == null) "––" else android.text.SpannableStringBuilder().apply {
            append(kmh.toString()); append(" km/h")
            setSpan(android.text.style.RelativeSizeSpan(0.32f), length - 5, length, 0)
        }
    }

    /** 프로필 4칸 한 줄. 돌려주는 순서는 Profile.values() 순서. */
    fun profileRow(): List<TextView> {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val views = Profile.values().map { p ->
            TextView(act).apply {
                text = p.label; textSize = 12.5f; gravity = Gravity.CENTER; maxLines = 1
                setPadding(dp(2), dp(7), dp(2), dp(7))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x0F000000) }
            }.also { row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(2); rightMargin = dp(2) }) }
        }
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        return views
    }

    /** cur = 지금 차에 보내 둔 프로필(파란 칸), usual = 평소 프로필(초록 테두리). */
    fun setProfileChip(v: TextView, cur: Boolean, usual: Boolean) {
        val bg = v.background as GradientDrawable
        bg.setColor(if (cur) BLUE else 0x0F000000)
        bg.setStroke(if (usual) dp(2) else 0, GREEN)
        v.setTextColor(if (cur) Color.WHITE else if (usual) GREEN else 0x8C000000.toInt())
        v.setTypeface(null, if (cur || usual) Typeface.BOLD else Typeface.NORMAL)
    }

    /** 가로 진행 막대(트랙 + 채움 + 빨간 눈금). */
    fun trackBar(heightDp: Int = 10): TrackBar = TrackBar(act).also {
        parent.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)).apply { topMargin = dp(4); bottomMargin = dp(4) })
    }

    /** 줄바꿈되는 칩 묶음. */
    fun chips(): FlowLayout = FlowLayout(act).apply { hGap = dp(6); vGap = dp(6) }
        .also { parent.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(4) }) }

    enum class ChipState { OK, BAD, NEUTRAL }

    fun chip(flow: FlowLayout, text: String, state: ChipState) {
        val color = when (state) { ChipState.OK -> GREEN; ChipState.BAD -> RED; ChipState.NEUTRAL -> GREY }
        flow.addView(TextView(act).apply {
            this.text = text; textSize = 12.5f; setTextColor(color); setTypeface(null, if (state == ChipState.BAD) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor((color and 0x00FFFFFF) or 0x1A000000) }
        })
    }

    /** 왼쪽 큰 글자 + 오른쪽 작은 설명 한 줄(다음 카메라 거리와 이름). */
    fun bigRow(): Pair<TextView, TextView> {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val big = TextView(act).apply { textSize = 34f; setTypeface(typeface, Typeface.BOLD); includeFontPadding = false }
        val info = TextView(act).apply { textSize = 12.5f; setTextColor(0xB3000000.toInt()); gravity = Gravity.END or Gravity.BOTTOM }
        row.addView(big, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        return big to info
    }
}

/** 가로 막대: 회색 트랙, 채움, (선택) 빨간 눈금. 다음 카메라 접근(눈금 = 신호 지점)과 구간 진행을 그린다. */
class TrackBar(ctx: Context) : View(ctx) {
    var progress = 0f
    var marker: Float? = null
    var fillColor = Ui.BLUE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(progress: Float, marker: Float?, color: Int) { this.progress = progress; this.marker = marker; fillColor = color; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        paint.color = 0x1F000000
        c.drawRoundRect(0f, 0f, w, h, r, r, paint)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) { paint.color = fillColor; c.drawRoundRect(0f, 0f, (w * p).coerceAtLeast(h), h, r, r, paint) }
        marker?.let { m -> paint.color = Ui.RED; val x = w * m.coerceIn(0f, 1f); c.drawRect(x - h * 0.2f, 0f, x + h * 0.2f, h, paint) }
    }
}

/** 자식을 왼쪽부터 늘어놓고 넘치면 다음 줄로 넘기는 단순 레이아웃(칩 묶음용). */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    var hGap = 0
    var vGap = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val maxW = width - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += ch.measuredWidth + hGap
            if (ch.measuredHeight > rowH) rowH = ch.measuredHeight
        }
        setMeasuredDimension(width, y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (ch.visibility == GONE) continue
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            ch.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + ch.measuredWidth, paddingTop + y + ch.measuredHeight)
            x += ch.measuredWidth + hGap
            if (ch.measuredHeight > rowH) rowH = ch.measuredHeight
        }
    }
}
