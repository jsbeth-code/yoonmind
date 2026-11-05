package com.jsbeth.camslow

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.util.Linkify
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 처음 설치 도움말(assets/help.txt). 지인에게 나눠 줄 때 따라 하기용.
 * 표기: "# " 제목, "## " 단락 제목, "[..]" 소제목, "- " 항목, "  → " 하위 항목, "1. " 순서. 주소는 눌러서 열린다.
 */
class HelpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val t = Theme.current(this)
        if (t.night) setTheme(android.R.style.Theme_Material_NoActionBar) else setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(40)) }
        val text = runCatching { assets.open("help.txt").bufferedReader().readText() }.getOrDefault("도움말을 읽지 못했습니다.")
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val tv = TextView(this).apply { setTextColor(t.fg); setLineSpacing(0f, 1.15f); setTextIsSelectable(true) }
            var top = dp(4); var left = 0
            when {
                line.startsWith("## ") -> { tv.text = line.removePrefix("## "); tv.textSize = 19f; tv.typeface = Typeface.DEFAULT_BOLD; top = dp(22) }
                line.startsWith("# ") -> { tv.text = line.removePrefix("# "); tv.textSize = 23f; tv.typeface = Typeface.DEFAULT_BOLD; top = 0 }
                line.startsWith("[") && line.endsWith("]") -> { tv.text = line.trim('[', ']'); tv.textSize = 16f; tv.typeface = Typeface.DEFAULT_BOLD; top = dp(14) }
                line.trimStart().startsWith("→") -> { tv.text = line.trim(); tv.textSize = 14.5f; left = dp(18) }
                line.startsWith("   ") -> { tv.text = line.trim(); tv.textSize = 14.5f; left = dp(22) }
                line.startsWith("- ") -> { tv.text = "•  " + line.removePrefix("- "); tv.textSize = 15f; left = dp(4) }
                else -> { tv.text = line.trim(); tv.textSize = 15f }
            }
            Linkify.addLinks(tv, Linkify.WEB_URLS)
            col.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = top; leftMargin = left })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(t.bg); addView(col) })
        title = "도움말"
    }
}
