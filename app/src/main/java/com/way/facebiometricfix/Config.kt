package com.way.facebiometricfix

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Xml
import io.github.libxposed.api.XposedModule
import org.xmlpull.v1.XmlPullParser
import java.io.FileInputStream

/**
 * 跨进程配置。
 *
 * - [KEY_DISABLED]：被关闭面容的应用包名，其余应用一律放行（默认全开）
 * - [KEY_ALLOW_FACE_STRONG]：是否允许人脸满足强认证（默认关）
 *
 * ===== 为什么不再依赖模块 App 常驻 =====
 *
 * 旧实现只靠 ContentResolver 查询 [PrefsProvider]，要求模块进程被拉起并存活。
 * 实测（uwuaosp）该进程空闲即被回收，AMS 重新拉起又失败：
 *
 *     Unable to launch app com.way.facebiometricfix/10611 for provider ...: process is bad
 *
 * 于是 system_server 永远读不到配置，模块整体失效 —— 连勾选放行的应用也失去人脸。
 *
 * ===== 四级读取（优先级从高到低）=====
 *
 *   1) [Settings.Secure]：系统原生存储。system_server 读它零成本、**不依赖任何进程**，
 *      也不受 /data 路径隐藏影响。写入方是本 App（需 WRITE_SECURE_SETTINGS，安装后
 *      `pm grant` 一次即可）。
 *   2) libxposed [XposedModule.openRemoteFile]：daemon 代开模块的 prefs 文件。
 *      （不用 getRemotePreferences —— 实测本机恒返回空集合且不抛异常）
 *   3) ContentProvider：模块进程恰好存活时的快速通道。
 *   4) 都失败则保留上次成功值；从未成功过时不接管（fail-safe）。
 */
internal object Config {

    const val PREFS_NAME = "config"
    const val KEY_DISABLED = "disabled_packages"
    const val KEY_ALLOW_FACE_STRONG = "allow_face_strong"
    const val AUTHORITY = "com.way.facebiometricfix.prefs"

    /** Settings.Secure 中使用的键（与 SharedPreferences 的键分开，避免混淆） */
    const val SETTING_DISABLED = "facebiometricfix_disabled_packages"
    const val SETTING_ALLOW_STRONG = "facebiometricfix_allow_face_strong"

    private const val PREFS_RELATIVE_PATH = "shared_prefs/config.xml"
    private const val COLUMN_KEY = "key"
    private const val COLUMN_VALUE = "value"
    private const val TAG = "FaceBiometricFix"

    /** 配置缓存有效期：避免每次判定都重新读取 */
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

    @Volatile
    private var module: XposedModule? = null

    fun attach(m: XposedModule?) {
        module = m
        lastLoad = 0L
        load()
    }

    fun isEnabled(packageName: String): Boolean {
        refresh()
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
        lastLoad = SystemClock.uptimeMillis()

        if (loadFromSettings()) return
        if (loadFromRemoteFile()) return
        if (loadFromProvider()) return

        Log.w(TAG, "config unavailable on all channels, keep previous: disabled=$disabled")
        lastLoad = 0L
    }

    /**
     * 通道 1：Settings.Secure。
     *
     * 由模块 App 写入（安装后 `pm grant ... WRITE_SECURE_SETTINGS`）。
     * system_server 读取无权限要求，且与 App 进程是否存活完全无关。
     */
    private fun loadFromSettings(): Boolean {
        val context = systemContext() ?: return false
        return runCatching {
            val cr = context.contentResolver
            // Settings 只支持字符串，包名用逗号分隔（包名不含逗号）
            val raw = Settings.Secure.getString(cr, SETTING_DISABLED)
            val allowRaw = Settings.Secure.getString(cr, SETTING_ALLOW_STRONG)
            if (raw == null && allowRaw == null) {
                return false    // 从未写入，交给下一条通道
            }
            val packages: Set<String> = raw
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toHashSet()
                ?: emptySet()
            disabled = packages
            allowFaceStrong = allowRaw == "1"
            loaded = true
            Log.i(TAG, "config loaded via Settings.Secure, allowFaceStrong=$allowFaceStrong, disabled=$disabled")
            true
        }.getOrElse {
            Log.w(TAG, "Settings.Secure failed: $it")
            false
        }
    }

    /** 通道 2：daemon 代开文件 + 自行解析 SharedPreferences XML。 */
    private fun loadFromRemoteFile(): Boolean {
        val m = module ?: return false
        return runCatching {
            val pfd = m.openRemoteFile(PREFS_RELATIVE_PATH)
            if (pfd == null) {
                Log.w(TAG, "remote file unavailable")
                return false
            }
            pfd.use { fd ->
                val packages = HashSet<String>()
                var allow = false
                var found = false

                val parser = Xml.newPullParser()
                parser.setInput(FileInputStream(fd.fileDescriptor), null)

                var event = parser.eventType
                var inDisabledSet = false
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "set" -> inDisabledSet =
                                KEY_DISABLED == parser.getAttributeValue(null, "name")
                            "boolean" -> {
                                if (KEY_ALLOW_FACE_STRONG == parser.getAttributeValue(null, "name")) {
                                    allow = "true" == parser.getAttributeValue(null, "value")
                                    found = true
                                }
                            }
                            else -> Unit
                        }
                        XmlPullParser.TEXT -> {
                            if (inDisabledSet) {
                                val pkg = parser.text?.trim()
                                if (!pkg.isNullOrEmpty()) {
                                    packages.add(pkg)
                                    found = true
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if ("set" == parser.name) inDisabledSet = false
                        }
                        else -> Unit
                    }
                    event = parser.next()
                }

                if (!found) {
                    Log.w(TAG, "remote file parsed but empty")
                    return false
                }
                disabled = packages
                allowFaceStrong = allow
                loaded = true
                Log.i(TAG, "config loaded via remote file, allowFaceStrong=$allow, disabled=$packages")
                true
            }
        }.getOrElse {
            Log.w(TAG, "remote file failed: $it")
            false
        }
    }

    /** 通道 3：ContentProvider（模块进程存活时）。 */
    private fun loadFromProvider(): Boolean {
        val context = systemContext() ?: return false

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
            if (rows <= 0) {
                Log.w(TAG, "provider query returned no rows")
                return@onSuccess
            }
            disabled = packages
            allowFaceStrong = allow
            loaded = true
            Log.i(TAG, "config loaded via provider, allowFaceStrong=$allow, disabled=$packages")
        }
        result.onFailure { Log.w(TAG, "provider query failed: $it") }

        return result.isSuccess && loaded
    }

    /** system_server 中取得 system context。 */
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
