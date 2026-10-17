# FaceBiometricFix（类原生适配 fork）

本仓库 fork 自 [WAYYYAW/FaceBiometricFix](https://github.com/WAYYYAW/FaceBiometricFix)，遵循上游 GPL-3.0。

上游面向 OPPO 系（ColorOS / AVIUM 等，OPPO / 一加 / 真我）机型。本 fork 在保留上游行为的前提下，做了三件事：**让它在类原生 / AOSP 系 ROM 上也能生效**、**加一个按应用开关**、**可选地让人脸参与强认证**。

## 本 fork 的改动

### 1. 兼容类原生 ROM 上报的 255 强度（`MainHook.kt`）

```kotlin
// 上游：只认原厂上报的 4095
sensorStrength == OEM_STRENGTH_MASK

// 本 fork：4095 与 255 同等放行
(sensorStrength == OEM_STRENGTH_MASK || sensorStrength == WEAK_FACE_STRENGTH)
```

类原生 ROM 会把厂商人脸 HAL 上报的 `4095` 归一化成标准 Class 2 的 `255`。上游能正常挂上 hook，但强度值对不上 `4095`，判定分支永远不命中，表现为「模块装好了却完全没有效果」，且日志里没有任何异常，很难排查。

### 2. 按应用开关（`SettingsActivity` / `PrefsProvider` / `Config`）

点开应用图标即为开关列表，**默认全部启用**，只持久化「被关掉」的包名。关掉的应用完全走系统原生判定，人脸不会被放行。

配置读取没有用 libxposed 的 `getRemotePreferences`：本机实测它恒返回空集合且不抛异常，界面等于摆设。改为模块进程用 `ContentProvider` 提供数据、system_server 侧用 system context 的 `ContentResolver` 读取，数据经 Binder 传递、不经过文件系统。改动后约 3 秒内生效，无需重启。

### 3. 可选：允许人脸满足强认证（默认关闭）

认证成功后，系统用 `BiometricService.isStrongBiometric()` 决定是否保留人脸 HAL 生成的 `HardwareAuthToken`；判 false 时令牌被丢弃，日志为 `Dropping authToken for non-strong biometric`，Keystore 里生物识别绑定的密钥因此无法解锁。

该开关只接管这个判定，且必须**同时**满足两个条件，顺序不可颠倒：

```
BiometricService.isStrongBiometric(sensorId)
  ① sensorId 是人脸？
  ② 读 BiometricService.mAuthSession 的 mOpPackageName → Config.isEnabled()？
       被排除的应用 → 直接走原生（保证「不勾选就没有人脸」始终成立）
  ③ AuthSession.mOperationId != 0（即调用方携带了 CryptoObject）？
  → 三条全过才放行
```

第 ② 步不可省略：这条路径上拿不到调用栈的包名，只能从当前 `AuthSession` 反查；漏掉它会让按应用开关在这条路径上失效，未勾选的应用照样拿到人脸。

第 ③ 步这样限制的原因：只有携带 `CryptoObject` 的请求，其操作级令牌才由系统在 KeyMint 会话内签发，放行后确实能拿到；而「只拿认证成功结果、自己直连 Keystore 索取 per-op 令牌」的应用（银行类多为此）不吃时间级令牌，放行只会让它们以为可用、实际解锁失败。读不到会话或包名时一律返回 false，**宁可不生效也不误放行**。

**风险提示**：开启该开关后，人脸在系统中被当作 Class 3 强认证，任何应用都能用人脸解锁其生物识别绑定的密钥，安全性实质下降。默认关闭，请自行评估。

其余文件差异只涉及构建链与本机环境适配（见下），不含额外功能。

## 实测环境与证据

测试机：OnePlus 13T（PKX110）/ AVIUM UI 16.2.1（`pagani-user`，SDK 36）/ LSPosed 2.2.0-it（API 102）。

| 观测点 | 实测值 |
| --- | --- |
| 传感器注册 | `BiometricService: Registering ID: 1008 Modality: 8 Strength: 255` |
| 传感器属性 | `dumpsys biometric` → `ID(1008), oemStrength: 255, modality 8` |
| 模块挂载 | `hooking biometrics in system_server` / `OK: PreAuthInfo app context hook` / `OK: app-only 4095 strength check` / `OK: strong biometric hook` |
| Class 3 预检 | `canAuthenticate: Authenticators=15` → `PreAuthInfo: Sensor ID: 1008 Status: 1`（已放行） |
| 实际认证 | `handleAuthenticate: StrengthRequested: 15` → `FaceAuthenticationClient` → `handleAuthenticationSucceeded(), sensorId: 1008` |
| 开关关闭对照 | `skip by config: com.github.android` → 人脸 `Status: 4`、指纹 `Status: 1` |
| 开关开启对照 | `treat face as strong: sensorId=1008` → `AuthSession: addAuthToken: 0` |

其中 `Status: 1` 即 `STATUS_OK`：原生实现下人脸 `255` 对请求 `15` 必然返回 `4`（`STATUS_INSUFFICIENT_STRENGTH`），所以返回 `1` 就证明强度闸门确实被放行。`addAuthToken: 0` 表示令牌注入成功。

## 已知限制

- 人脸仍不被判定为 strong（未开第三个开关时）：日志中持续出现 `Dropping authToken for non-strong biometric, id: 1008`，将密钥绑定至生物识别的应用（`setUserAuthenticationRequired`）仍只能使用指纹。
- 银行类应用多不携带 `CryptoObject`：它们拿到认证结果后自行直连 Keystore 索取操作级令牌（实测招商银行 `CmbSecurityTEEKey`，`USER_AUTH_TYPE=4` 但无 `USER_AUTH_TIMEOUT`，即 per-operation），这类密钥只接受 KeyMint 当次会话签发的令牌，本模块不介入，也不会 hook 这类应用。
- 人脸资格还受摄像头状态影响：`modality 8` 且摄像头被占用时 `PreAuthInfo` 返回 `6`（`STATUS_HARDWARE_UNAVAILABLE`），这不是模块问题。
- 目标 ROM 上报的强度若不是 `4095` / `255`，用 `logcat | grep "Registering ID"` 看 `Strength:` 实际值，再同步常量即可。
- 第三个开关的传感器判定使用硬编码 id（本机人脸为 `1008`）。换机型需同步 `MainHook.FACE_SENSOR_ID`。
