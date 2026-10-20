package com.way.facebiometricfix

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log

/**
 * 跨进程配置。
 *
 * - [KEY_DISABLED]：被关闭面容的应用包名，其余应用一律放行（默认全开）
 * - [KEY_ALLOW_FACE_STRONG]：是否允许人脸满足强认证（默认关）
 *
 * 数据由模块进程经 [PrefsProvider] 提供，system_server 侧用 system context 的
 * ContentResolver 读取，经 Binder 传递、不经过文件系统。
 *
 * ===== 2026-10-20 修复：配置读取的 fail-open 缺陷 =====
 *
 * 原实现把「读取结果」无条件写回缓存：
 *
 *     disable = packages    // packages 默认是空集合
 *
 * 而开机早期模块 App 的进程还没起来时，system_server 查 provider 会失败
 * （实测日志：`Failed to find provider info ...` 紧接着
 *  `Unable to launch app ... for provider ...: process is bad`），此时
 * query 拿不到有效行，于是 **空集合被当成「没有任何应用被禁用」写进缓存**，
 * 结果所有应用（包括用户明确取消勾选的银行类 App）都能继续用面容。
 *
 * 修复要点：
 * 1) [PrefsProvider.query] 永远至少返回 allow_face_strong 一行，因此
 *    「读到 0 行」等价于 provider 不可用，必须判为失败，不得覆盖缓存；
 * 2) 失败时把 lastLoad 归零，下一次判定立刻重试（而不是等满 TTL）；
 * 3) 引入 [loaded] 标记：在成功读到有效配置之前，[isEnabled] 一律返回 false，
 *    即**不做任何接管、完全走系统原生行为**（fail-safe），
 *    避免「配置未知」被误解成「全部允许」。
 */
internal object Config {

    const val PREFS_NAME = "config"
    const val KEY_DISABLED = "disabled_packages"
    const val KEY_ALLOW_FACE_STRONG = "allow_face_strong"
    const val AUTHORITY = "com.way.facebiometricfix.prefs"

    private const val COLUMN_KEY = "key"
    private const val COLUMN_VALUE = "value"
    private const val TAG = "FaceBiometricFix"

    /** 配置缓存有效期：避免每次判定都跨进程查询 */
    private const val TTL_MS = 3000L

    @Volatile
    private var disabled: Set<String> = emptySet()

    @Volatile
    private var allowFaceStrong = false

    /** 是否至少成功读到过一次有效配置。false 时一律不接管。 */
    @Volatile
    private var loaded = false

    @Volatile
    private var lastLoad = 0L

    /** 在 system_server 启动与热重载时预读一次。日志走 logcat，不依赖模块实例。 */
    fun attach() {
        lastLoad = 0L
        load()
    }

    fun isEnabled(packageName: String): Boolean {
        refresh()
        // 配置尚未可靠加载：不接管，交给系统原生逻辑，避免失败开放。
        if (!loaded) return false
        return packageName !in disabled
    }

    fun isFaceStrongAllowed(): Boolean {
        refresh()
        if (!loaded) return false
        return allowFaceStrong
    }

    private fun refresh() {
        if (SystemClock.uptimeMillis() - lastLoad > TTL_MS) {
            load()
        }
    }

    private fun load() {
        // 先记时间戳，避免读取过程中被重复触发
        lastLoad = SystemClock.uptimeMillis()

        val context = systemContext()
        if (context == null) {
            Log.w(TAG, "system context unavailable, keep config as-is")
            lastLoad = 0L
            return
        }

        val result = runCatching {
            val packages = HashSet<String>()
            var allow = false
            var rows = 0
            context.contentResolver
                .query(Uri.parse("content://$AUTHORITY"), null, null, null, null)
                ?.use { cursor ->
                    val keyIndex = cursor.getColumnIndex(COLUMN_KEY)
                    val valueIndex = cursor.getColumnIndex(COLUMN_VALUE)
                    if (keyIndex >= 0 && valueIndex >= 0) {
                        while (cursor.moveToNext()) {
                            rows++
                            when (cursor.getString(keyIndex)) {
                                KEY_DISABLED -> cursor.getString(valueIndex)?.let { packages.add(it) }
                                KEY_ALLOW_FACE_STRONG -> allow = cursor.getString(valueIndex) == "1"
                            }
                        }
                    }
                }
            Triple(packages, allow, rows)
        }

        result.onSuccess { (packages, allow, rows) ->
            // provider 必定至少返回 allow_face_strong 一行；0 行说明没真正读到，
            // 此时绝不能把空集合写进缓存（否则等于「谁都没被禁用」）。
            if (rows <= 0) {
                Log.w(TAG, "config query returned no rows (provider unavailable?), keep previous: disabled=$disabled")
                lastLoad = 0L
                return@onSuccess
            }
            disabled = packages
            allowFaceStrong = allow
            loaded = true
            Log.i(TAG, "config loaded, allowFaceStrong=$allow, disabled=$packages")
        }
        result.onFailure {
            Log.w(TAG, "read config failed: $it, keep previous: disabled=$disabled")
            lastLoad = 0L
        }
    }

    /** system_server 中取得 system context，用于访问 ContentProvider。 */
    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)
        activityThread
            .getDeclaredMethod("getSystemContext")
            .apply { isAccessible = true }
            .invoke(current) as? Context
    }.getOrNull()
}
