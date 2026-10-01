package com.example.auto_turn_page

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView

class PageTurnService : AccessibilityService() {

    companion object {
        const val PREFS = "cfg"
        const val KEY_SEC = "sec"
        const val KEY_OVERLAY = "overlay"
        const val KEY_PROGRESS = "progress"
        const val KEY_APPS = "apps"
        const val KEY_RUNNING = "run"
        const val KEY_SERVICE = "service"
        const val KEY_ALPHA = "alpha"
        val DEFAULT_APPS = setOf("com.tencent.weread")

        // ponytail: static 當跨 Activity/Service 通訊，存 DB / Intent 是多餘的
        // 三條件 AND 才翻：無障礙開（系統）＋ serviceOn（設定頁總閘）＋ running（球點火的本次會話）
        @Volatile var serviceOn = false
        @Volatile var running = false
        @Volatile var intervalMs = 5000L
        @Volatile var overlayOn = true
        @Volatile var progressOn = true
        @Volatile var allowedApps: Set<String> = DEFAULT_APPS
        @Volatile var ballAlpha = 50 // 懸浮球透明度 10~100
        @Volatile var currentPkg: String = ""
        @Volatile var instance: PageTurnService? = null

        fun loadPrefs(ctx: Context) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            serviceOn = p.getBoolean(KEY_SERVICE, false)
            running = p.getBoolean(KEY_RUNNING, false)
            intervalMs = p.getInt(KEY_SEC, 5).coerceIn(3, 30) * 1000L
            overlayOn = p.getBoolean(KEY_OVERLAY, true)
            progressOn = p.getBoolean(KEY_PROGRESS, true)
            allowedApps = p.getStringSet(KEY_APPS, DEFAULT_APPS) ?: DEFAULT_APPS
            ballAlpha = p.getInt(KEY_ALPHA, 50).coerceIn(10, 100)
        }

        fun saveRunning(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RUNNING, running)
                .putBoolean(KEY_SERVICE, serviceOn).apply()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var nextFireAt = 0L
    private var lastAutoTap = 0L
    private var lastPkgAt = 0L // currentPkg 最後更新時間（事件或可見視窗）
    private var lastTickAt = 0L // 看門狗用：tick 最後心跳
    // 進自家一定有事件（自家非安全視窗）→ 可信；在自家時永不翻、藏球
    @Volatile var inOwnApp = false

    /** 三條件 AND：總閘開＋球點火才算在翻 */
    private fun active() = serviceOn && running

    // 每 200ms 跑一次：順便推左側進度條，比 postDelayed(interval) 省一個 timer
    private val tick = object : Runnable {
        override fun run() {
            try {
                if (!active()) return
                lastTickAt = SystemClock.uptimeMillis()
                refreshForeground() // 前景事件不可靠（安全視窗不送），用 active window 校準
                // 自家頁只擋翻頁不藏球（綠條由 updateProgress 按 flipBlocked 藏）
                if (overlayOn && ball == null && !surelyOutside()) showOverlay()
                val now = SystemClock.uptimeMillis()
                if (now >= nextFireAt && !flipBlocked()) {
                    tapNextPage()
                    lastAutoTap = now
                    nextFireAt = now + intervalMs
                }
                refreshBall() // 球的顯示每輪自癒，不可能再卡在錯的 state
                updateProgress()
            } catch (_: Exception) {
                // 吞掉保活：finally 會重排
            } finally {
                // finally 保底重排：迴圈殺不死，stop() 靠 running=false 斷尾
                if (active()) handler.postDelayed(this, 200)
            }
        }
    }

    // tick 被系統拔掉（onInterrupt/殺進程邊緣）時 2 秒內復活，否則球卡 ❚❚ 卻不翻
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                if (active() && SystemClock.uptimeMillis() - lastTickAt > 1500) {
                    nextFireAt = SystemClock.uptimeMillis() + intervalMs
                    handler.removeCallbacks(tick)
                    handler.post(tick)
                }
            } catch (_: Exception) {
            } finally {
                handler.postDelayed(this, 2000)
            }
        }
    }

    /** 看得見的 active window；瞬間視窗（音量/彈窗）當沒看見 */
    private fun visiblePkg(): String? {
        val v = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        if (v.isNullOrEmpty() || transientPkgs.contains(v)) return null
        return v
    }

    /** 地面真相：看得見就信眼睛；盲（安全視窗）只信 3 秒內的新事件，過期放行 */
    private fun refreshForeground() {
        visiblePkg()?.let {
            if (it != currentPkg) {
                currentPkg = it
                lastPkgAt = SystemClock.uptimeMillis()
            }
            if (it == packageName) inOwnApp = true else inOwnApp = false
        }
    }

    /** 真的在外面才回 true。註：自家設定頁不算外面（翻頁由 inOwnApp 擋，球要留）；不在白名單才不點 */
    private fun surelyOutside(): Boolean {
        visiblePkg()?.let {
            if (it == packageName) return false
            return !isAllowed(it)
        }
        if (currentPkg.isEmpty() || isAllowed(currentPkg)) return false
        return SystemClock.uptimeMillis() - lastPkgAt < 3000
    }

    /** 自家頁永不翻（進來自家一定有事件，可信）；其餘看門控 */
    private fun flipBlocked(): Boolean {
        if (inOwnApp) return true
        return surelyOutside()
    }

    // ---- overlay ----
    private var wm: WindowManager? = null
    private var ball: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var barFill: View? = null

    override fun onServiceConnected() {
        instance = this
        loadPrefs(this)
        // 剛連上時包名未知，先當外面 3 秒做校準，避免重開瞬間在別人家裡亂點
        currentPkg = "unknown"
        lastPkgAt = SystemClock.uptimeMillis()
        if (overlayOn && !inOwnApp) showOverlay()
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        if (serviceOn && running) start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: ""
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = event.className?.toString() ?: ""
                // ponytail: 球/bar 自身懸浮窗也會送自家包名事件（非 MainActivity），忽略否則 show→hide 無限閃
                if (pkg == packageName && !cls.contains("MainActivity")) return
                if (pkg == packageName) {
                    inOwnApp = true
                } else if (pkg.isNotEmpty() && !transientPkgs.contains(pkg)) {
                    inOwnApp = false
                }
                // 自家 overlay 不參與前景包名判定；瞬間視窗亦排除
                if (pkg.isNotEmpty() && !transientPkgs.contains(pkg) && pkg != packageName) {
                    currentPkg = pkg
                    lastPkgAt = SystemClock.uptimeMillis()
                }
                if (pkg.isNotEmpty()) onForeground(pkg)
            }
            // 使用者自己左右翻頁 → 內容變化 → 秒數重數
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (active() && pkg.isNotEmpty() && isAllowed(pkg)) {
                    // 自己剛自動點的不算（300ms 內），否則節奏永遠被往後推沒差別；使用者手動的一定超過
                    val now = SystemClock.uptimeMillis()
                    // ponytail: 首翻前不重計——進書載入的內容事件 burst 會一直推後 nextFireAt，綠條重長好幾次
                    if (lastAutoTap > 0 && now - lastAutoTap > 1000) {
                        nextFireAt = now + intervalMs
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // ponytail: 點位本來就按當下 metrics 算，直橫皆通；這裡只夾球回可視範圍＋綠條容器改新高度
        val (sw, sh) = screenSize()
        val b = ball
        ballParams?.let { bp ->
            bp.x = bp.x.coerceIn(0, (sw - dp(56)).coerceAtLeast(0))
            bp.y = bp.y.coerceIn(0, (sh - dp(56)).coerceAtLeast(0))
            if (b != null) try { wm?.updateViewLayout(b, bp) } catch (_: Exception) {}
        }
        (ball?.tag as? View)?.let { bar ->
            (bar.tag as? WindowManager.LayoutParams)?.let { pp ->
                pp.height = sh
                try { wm?.updateViewLayout(bar, pp) } catch (_: Exception) {}
            }
        }
        updateProgress()
    }

    override fun onInterrupt() {
        handler.removeCallbacks(tick)
        // 系統拔掉迴圈時自動復活，否則球會卡在 ❚❚ 卻不翻（看門狗是第二道）
        if (active()) {
            nextFireAt = SystemClock.uptimeMillis() + intervalMs
            handler.postDelayed(tick, 500)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // running 故意不清除：重開無障礙時 onServiceConnected 自動續翻
        handler.removeCallbacks(tick)
        handler.removeCallbacks(watchdog)
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    /** 球點火：總閘沒開時點球只提示，不開翻；點火即人在閱讀頁，清掉自家旗標 */
    fun start() {
        if (!serviceOn) return
        inOwnApp = false
        running = true
        saveRunning(this)
        lastAutoTap = 0 // 首翻前哨兵：上面的重計邏輯靠它忽略進書 burst
        nextFireAt = SystemClock.uptimeMillis() + intervalMs
        handler.removeCallbacks(tick)
        handler.post(tick)
        refreshBall()
    }

    /** 停本次會話（總閘保持，進書點球可再開） */
    fun stop() {
        running = false
        saveRunning(this)
        handler.removeCallbacks(tick)
        // tick 停了不會再調 updateProgress，這裡直接把綠條清掉
        (ball?.tag as? View)?.visibility = View.GONE
        refreshBall()
    }

    /** 設定頁改完設定後呼叫 */
    fun applySettings() {
        loadPrefs(this)
        if (!overlayOn) hideOverlay()
        else showOverlay() // 自家頁也要留球（只擋翻頁），不能加 !inOwnApp 條件
        if (active()) {
            // 切到非白名單 app：tick 自己跳過不點，秒數照跑
            nextFireAt = SystemClock.uptimeMillis() + intervalMs
            handler.removeCallbacks(tick)
            handler.post(tick)
        } else {
            handler.removeCallbacks(tick)
        }
        refreshBall()
        updateProgress()
    }

    private fun isAllowed(pkg: String): Boolean {
        if (allowedApps.isEmpty()) return true // 清空 = 全部允許
        if (pkg.isEmpty()) return true
        return allowedApps.contains(pkg)
    }

    // 音量條、權限彈窗這類瞬間視窗：不處理，否則按個音量就斷掉本次翻頁
    private val transientPkgs = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.android.packageinstaller"
    )

    private fun isLauncher(pkg: String): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(home, 0)
            .any { it.activityInfo.packageName == pkg }
    }

    /** 前景 App 切換路由 */
    private fun onForeground(pkg: String) {
        if (transientPkgs.contains(pkg)) return
        if (pkg == packageName) {
            // 自家設定頁：球留著（點了也不翻），綠條清掉
            if (overlayOn) {
                showOverlay()
                refreshBall()
                updateProgress()
            } else hideOverlay()
            return
        }
        if (isAllowed(pkg)) {
            if (overlayOn) {
                showOverlay()
                refreshBall()
                updateProgress()
            }
            return
        }
        if (isLauncher(pkg)) {
            if (active()) stop()
            hideOverlay() // 清掉所有 App 回到桌面：球也消失，重開本 App 才回來
        } else if (active()) {
            // 切去別的 App（含銀行這類安全 App）：一律停本次會話，總閘保持；
            // 誤停零成本——回閱讀頁點球就繼續
            stop() // 綠條一起清；球留著點一下就回來
        }
    }

    private fun tapNextPage() {
        val (w, h) = screenSize()
        // ponytail: 點右中避開上下選單欄；微信讀書點右半屏即下一頁
        val x = w * 0.8f
        val y = h * 0.5f
        // 只有 moveTo 是零長度手勢，部分 ROM 直接丟掉，補 1px 才會真的 dispatch
        val path = Path().apply { moveTo(x, y); lineTo(x + 1, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        if (!ok) stop() // 手勢被系統拒收（遊戲/金融頁）：停掉免得空轉
    }

    /** 給設定頁測試鍵用：回傳 false = 手勢被系統拒絕 */
    fun testTap(cb: (Boolean) -> Unit) {
        val (w, h) = screenSize()
        val x = w * 0.8f
        val y = h * 0.5f
        val path = Path().apply { moveTo(x, y); lineTo(x + 1, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { cb(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { cb(false) }
        }, null)
    }

    // ---- 懸浮球 + 左側進度條 ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ponytail: dispatchGesture 吃螢幕座標；displayMetrics 在橫屏/手勢列下會偏小，API 30+ 用真實螢幕尺寸
    private fun screenSize(): Pair<Int, Int> {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val w = getSystemService(WINDOW_SERVICE) as WindowManager
                val b = w.maximumWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
            }
        } catch (_: Exception) {}
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun showOverlay() {
        if (ball != null) {
            refreshBall()
            return
        }
        if (!Settings.canDrawOverlays(this)) return
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w

        // 左側進度條：淡綠色，由下往上長（5dp 細條）
        val (sw, sh) = screenSize()
        val barH = sh
        val bar = FrameLayout(this).apply {
            addView(View(context).apply {
                setBackgroundColor(Color.parseColor("#A5D6A7")) // 淡綠
                barFill = this
            }, FrameLayout.LayoutParams(dp(5), 0, Gravity.BOTTOM))
        }
        val barParams = WindowManager.LayoutParams(
            dp(5), barH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.TOP; x = 0; y = 0 }
        bar.tag = barParams
        w.addView(bar, barParams)

        // 懸浮球：點一下點火/熄火本次會話，長按回設定頁，可拖
        val b = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#802196F3"))
            }
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumWidth = dp(56)
            minimumHeight = dp(56)
        }
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = sw - dp(90)
            y = sh / 2
        }
        var downX = 0f; var downY = 0f; var baseX = 0; var baseY = 0; var moved = false
        // OnTouchListener 吃掉事件後系統長按不會觸發，自己計 600ms：長按回設定頁
        val longPress = Runnable {
            if (!moved) {
                moved = true // 長按不兼算點按，UP 不再 toggle（否則長按回設定還會順手開/關一次）
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    baseX = bp.x; baseY = bp.y; moved = false
                    b.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (dx * dx + dy * dy > dp(10) * dp(10)) moved = true
                    if (moved) {
                        b.removeCallbacks(longPress)
                        bp.x = baseX + dx; bp.y = baseY + dy
                        wm?.updateViewLayout(v, bp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    b.removeCallbacks(longPress)
                    // 球點火/熄火本次會話；總閘沒開時只提示，不開翻
                    if (!moved) {
                        if (!serviceOn) {
                            android.widget.Toast.makeText(this, "先去設定頁開啟翻頁服務", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (running) stop() else start()
                    }
                }
                MotionEvent.ACTION_CANCEL -> b.removeCallbacks(longPress)
            }
            true
        }
        ball = b
        ballParams = bp
        // bar 記在 ball 上一起清掉（單參數 tag，不需 R.id）
        b.tag = bar
        w.addView(b, bp)
        refreshBall()
        updateProgress()
    }

    private fun refreshBall() {
        ball?.text = if (active()) "❚❚" else "▶"
        ball?.alpha = ballAlpha / 100f
        ball?.visibility = if (overlayOn) View.VISIBLE else View.GONE
    }

    private fun updateProgress() {
        val bar = (ball?.tag as? View) ?: return
        // 非白名單 app：背景下不可翻頁，綠條一起清掉
        val show = overlayOn && progressOn && active() && !flipBlocked()
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val fill = barFill ?: return
        val now = SystemClock.uptimeMillis()
        val frac = ((nextFireAt - now).toFloat() / intervalMs).coerceIn(0f, 1f)
        val total = screenSize().second
        val lp = fill.layoutParams as FrameLayout.LayoutParams
        lp.height = (total * (1f - frac)).toInt()
        fill.layoutParams = lp
    }

    private fun hideOverlay() {
        val w = wm ?: return
        ball?.let { b ->
            try { w.removeView(b) } catch (_: Exception) {}
            (b.tag as? View)?.let { bar ->
                try { w.removeView(bar) } catch (_: Exception) {}
            }
        }
        ball = null
        ballParams = null
        barFill = null
        wm = null
    }
}
