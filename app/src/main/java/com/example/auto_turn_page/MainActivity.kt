package com.example.auto_turn_page

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var secLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var overlayBox: CheckBox
    private lateinit var progressBox: CheckBox
    private lateinit var alphaLabel: TextView
    private lateinit var alphaSeek: SeekBar
    private lateinit var appsSummary: TextView
    private lateinit var toggle: Button

    private fun prefs() = getSharedPreferences(PageTurnService.PREFS, Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PageTurnService.loadPrefs(this)
        val p = prefs()

        secLabel = TextView(this)
        seek = SeekBar(this).apply {
            max = 27 // 3~30s
            progress = (p.getInt(PageTurnService.KEY_SEC, 5) - 3).coerceIn(0, 27)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    secLabel.text = "秒數：${v + 3} 秒"
                    save()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        overlayBox = CheckBox(this).apply {
            text = "懸浮球（可拖，點一下點火/熄火，長按回設定）"
            isChecked = p.getBoolean(PageTurnService.KEY_OVERLAY, true)
            setOnCheckedChangeListener { _, _ -> save() }
        }
        progressBox = CheckBox(this).apply {
            text = "左側秒數進度條（淡綠色）"
            isChecked = p.getBoolean(PageTurnService.KEY_PROGRESS, true)
            setOnCheckedChangeListener { _, _ -> save() }
        }
        alphaLabel = TextView(this)
        alphaSeek = SeekBar(this).apply {
            max = 90 // 10~100
            progress = (p.getInt(PageTurnService.KEY_ALPHA, 50) - 10).coerceIn(0, 90)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    alphaLabel.text = "懸浮球透明度：${v + 10}%"
                    save()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        appsSummary = TextView(this)
        val pickApps = Button(this).apply {
            text = "選擇可翻頁的 App"
            setOnClickListener { pickApps() }
        }
        val accessBtn = Button(this).apply {
            text = "1. 開無障礙權限"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val overlayBtn = Button(this).apply {
            text = "2. 開懸浮窗權限"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        status = TextView(this)
        toggle = Button(this).apply { setOnClickListener { toggle() } }
        val test = Button(this).apply {
            text = "測試點一下"
            setOnClickListener {
                val s = PageTurnService.instance
                if (s == null) {
                    Toast.makeText(this@MainActivity, "Service 沒連上：無障礙沒啟用", Toast.LENGTH_SHORT).show()
                } else {
                    s.testTap { ok ->
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, if (ok) "手勢 OK，有觸發" else "手勢被拒絕", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // ponytail: 純程式碼排版，少一堆 xml
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(accessBtn)
            addView(overlayBtn)
            addView(secLabel)
            addView(seek)
            addView(overlayBox)
            addView(progressBox)
            addView(alphaLabel)
            addView(alphaSeek)
            addView(TextView(context).apply { text = "可翻頁的 App：" })
            addView(appsSummary)
            addView(pickApps)
            addView(status)
            addView(toggle)
            addView(test)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        secLabel.text = "秒數：${seek.progress + 3} 秒"
        alphaLabel.text = "懸浮球透明度：${alphaSeek.progress + 10}%"
    }

    override fun onResume() {
        super.onResume()
        // ponytail: 回自家=本次閱讀結束，總閘+會話一起關（stop 順手清綠條，比等事件/200ms tick 快且不偶發）
        if (PageTurnService.serviceOn || PageTurnService.running) {
            PageTurnService.serviceOn = false
            PageTurnService.running = false
            PageTurnService.instance?.stop()
            PageTurnService.saveRunning(this)
        }
        save() // 從權限頁回來時把 overlay 重掛上
        refresh()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        prefs().edit()
            .putInt(PageTurnService.KEY_SEC, seek.progress + 3)
            .putBoolean(PageTurnService.KEY_OVERLAY, overlayBox.isChecked)
            .putBoolean(PageTurnService.KEY_PROGRESS, progressBox.isChecked)
            .putInt(PageTurnService.KEY_ALPHA, alphaSeek.progress + 10)
            .putStringSet(PageTurnService.KEY_APPS, PageTurnService.allowedApps)
            .apply()
        PageTurnService.loadPrefs(this)
        PageTurnService.instance?.applySettings()
        refreshAppsSummary()
    }

    private fun appLabel(pkg: String): String {
        return try {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        } catch (_: Exception) { pkg }
    }

    private fun refreshAppsSummary() {
        val set = PageTurnService.allowedApps
        appsSummary.text = if (set.isEmpty()) "全部允許（都翻）"
            else set.sorted().joinToString("\n") { "• ${appLabel(it)}" }
    }

    /** 白名單改多選清單：勾選才翻，全不勾 = 全部允許 */
    private fun pickApps() {
        val pm = packageManager
        val list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).sortedBy { it.loadLabel(pm).toString().lowercase() }
        val pkgs = list.map { it.activityInfo.packageName }
        val labels = list.map { "${it.loadLabel(pm)} (${it.activityInfo.packageName})" }.toTypedArray()
        val checked = pkgs.map { PageTurnService.allowedApps.contains(it) }.toBooleanArray()
        val orig = PageTurnService.allowedApps
        AlertDialog.Builder(this)
            .setTitle("可翻頁的 App（全不勾 = 全部允許）")
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                val s = PageTurnService.allowedApps.toMutableSet()
                if (on) s.add(pkgs[which]) else s.remove(pkgs[which])
                PageTurnService.allowedApps = s
            }
            .setPositiveButton("確定") { _, _ -> save() }
            .setNegativeButton("取消") { _, _ -> PageTurnService.allowedApps = orig }
            .show()
    }

    private fun toggle() {
        if (PageTurnService.serviceOn) {
            // 關總閘：本次會話一起停
            PageTurnService.serviceOn = false
            PageTurnService.instance?.stop()
            PageTurnService.running = false
            PageTurnService.saveRunning(this)
        } else {
            if (!isServiceOn()) {
                status.text = "狀態：請先開無障礙權限並啟用 自動翻頁"
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            // 開總閘：只掛球不開翻，進閱讀頁點球才點火
            PageTurnService.serviceOn = true
            PageTurnService.saveRunning(this)
            PageTurnService.instance?.applySettings()
            Toast.makeText(this, "已開啟：去閱讀頁點 ▶ 開始", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun refresh() {
        val on = isServiceOn()
        val conn = PageTurnService.instance != null
        val cur = PageTurnService.currentPkg.ifEmpty { "?" }
        status.text = "狀態：無障礙=${if (on) "開" else "關"}，連線=${if (conn) "OK" else "無"}" +
            "，服務=${if (PageTurnService.serviceOn) "開" else "關"}" +
            "，翻頁=${if (PageTurnService.running) "跑" else "停"}，當前=${cur}"
        toggle.text = if (PageTurnService.serviceOn) "停止翻頁服務" else "開啟翻頁服務"
        refreshAppsSummary()
    }

    private fun isServiceOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        // 系統存的是 com.example.auto_turn_page/com.example.auto_turn_page.PageTurnService 全寫，短寫比對永遠 false
        return flat.split(':').any { it.contains(packageName, ignoreCase = true) && it.contains("PageTurnService", ignoreCase = true) }
    }
}
