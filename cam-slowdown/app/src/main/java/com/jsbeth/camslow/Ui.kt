package com.jsbeth.camslow

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** XML 레이아웃 없이 코드로 화면을 짜기 위한 작은 도우미. */
class Ui(private val act: Activity) {
    val root = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(16)
        setPadding(p, p, p, p)
    }

    fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    fun show() = act.setContentView(ScrollView(act).apply { addView(root) })

    fun text(s: String = "", size: Float = 15f, bold: Boolean = false): TextView =
        TextView(act).apply {
            text = s; textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }.also { root.addView(it) }

    fun button(label: String, onClick: () -> Unit): Button =
        Button(act).apply { text = label; setOnClickListener { onClick() } }.also { root.addView(it) }

    fun row(vararg views: View): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            (v.parent as? LinearLayout)?.removeView(v)
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }.also { root.addView(it) }

    fun number(label: String, value: String, decimal: Boolean = true): EditText {
        text(label, 13f)
        return EditText(act).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) or InputType.TYPE_NUMBER_FLAG_SIGNED
        }.also { root.addView(it) }
    }

    fun check(label: String, value: Boolean): CheckBox =
        CheckBox(act).apply { text = label; isChecked = value }.also { root.addView(it) }

    fun multiline(label: String, value: String): EditText {
        text(label, 13f)
        return EditText(act).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }.also { root.addView(it) }
    }
}
