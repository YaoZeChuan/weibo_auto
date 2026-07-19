# 会话上下文摘要（导出）

导出时间：2026-07-18  
项目：`C:\projects\AI\weibo\Android-Auto-Api`  
当前分支：`master`  
包名 / applicationId：`cn.vove7.weibo.auto`  
设备验证机型：小米 `M2007J17C`（Android 12）

---

## 1. 目标与范围

把原 `Android-Auto-Api` demo 改造成「微博助手」：

- 基于无障碍（`AccessibilityApi`）操作微博
- UI：Jetpack Compose + MVVM
- 存储：Room（账号列表、任务记录）
- 本阶段重点：刷新账号、检测超 LIKE、任务骨架；业务脚本可继续扩展

---

## 2. 已完成

### 工程 / 架构

- 改造 `:app` 模块，包名改为 `cn.vove7.weibo.auto`
- Compose + Material3 + Room + KSP + Coil
- 接入 `project(':accessibility')`（依赖 core）
- 无障碍服务：`WeiboAccessibilityService`
- 保活前台服务：`KeepAliveService`
- 编译安装约定：**默认使用 `./gradlew installDebug`**

### 首页 UI

- 无障碍状态条（区分：系统已授权 / 服务已连接 / 可操作）
- 账号卡片（多选、删除、超 LIKE 状态与经验值）
- 按钮：刷新 / 超like / 启动 / **测试打开超LIKE页**
- 启动后任务选择弹窗（发帖、签到、浏览、检测超like）— 任务执行仍多为占位

### 刷新账号

路径：打开微博 → 底部「我」→ 设置 → 账号管理 → 抓昵称入库

- 实现：`WeiboAccountDiscovery` + `AccountRepository.refreshAccounts`
- 过滤状态栏脏数据（时间、网速、电量、非微博包名）
- 结束后：回微博首页 → 切回助手（**不强杀微博**）

### 超 LIKE 检测

路径：账号管理切号 → deep link 打开超 LIKE 页 → 点「超LIKE」→ 读「经验值：xx」

- `exp > 80` 视为已点亮
- 字段：`WeiboAccount.superLikeLit`、`superLikeExp`（Room version=2）
- 有勾选测勾选，无勾选测全部
- 实现：`SuperLikeChecker` + `WeiboNavigator`

### 打开超 LIKE deep link

已验证可用 URI（与 adb 一致）：

```text
sinaweibo://cardlist?containerid=23114044cc042c1e6385c391f94e8094939df5_-_profile_allbadge
component: com.sina.weibo/.page.NewCardListActivity
```

常量：`WeiboConsts.SUPER_LIKE_CONTAINER_ID`  
已删除：`SUPER_LIKE_CONTAINER_ID_ALT`

### 库层修复

- `ViewFinder.traverseAllNode`：递归必须共享 `nodeSet`，并限制 `MAX_TRAVERSE_DEPTH=40`  
  修复无障碍树遍历 `StackOverflowError`
- `printWithChild` 增加 visited / maxDepth 防环

### 无障碍状态误判修复

- 旧逻辑只看 `baseService != null`，进程重启会误显示未开启
- 新逻辑区分：系统设置授权 vs 服务连接；授权未连接会短时轮询

---

## 3. 关键代码结构

```text
app/src/main/java/cn/vove7/weibo/auto/
├── WeiboApp.kt / MainActivity.kt
├── service/
│   ├── WeiboAccessibilityService.kt
│   └── KeepAliveService.kt
├── data/          # Room entity/dao/db/repo
├── domain/
│   ├── model/TaskType.kt
│   ├── task/TaskRunner.kt          # NoOp 占位
│   └── weibo/
│       ├── WeiboConsts.kt
│       ├── WeiboNavigator.kt       # 导航 / deep link / 读经验值
│       ├── WeiboAccountDiscovery.kt
│       ├── SuperLikeChecker.kt
│       └── WeiboAppController.kt   # 回微博首页 + 回助手
└── ui/dashboard/                   # Compose 首页
```

---

## 4. 重要结论 / 坑

### 测试按钮能开、完整流程开不了（历史问题）

| 场景 | 前台 | 结果 |
|------|------|------|
| 测试打开超LIKE页 | 助手 App | 通常成功 |
| 完整超like 检测 | 微博 | 易失败 |

原因：

1. **BAL**：后台 App `startActivity` 可能被静默拦截  
2. 应用内 `Runtime.exec("am start")` **无 shell 权限**，会 `Permission Denial`（exit=255）  
3. **回桌面再打开是错误策略**（MIUI 上 log 显示仍停在 `com.miui.home`，deep link 被吞）

当前策略：

- **不回桌面**
- 用 `AccessibilityApi.baseService` 作为 Context 发 Intent
- 校验 `NewCardList` / 超LIKE 相关文案
- 去掉 am start 兜底与 ALT containerid

### 账号 uid

页面通常不暴露真实 uid，本地用 `name_<hash>` 稳定伪 id。

### 脏账号文本

`ScreenTextFinder` 会扫到状态栏；已用包名 + 区域 + 正则过滤。

---

## 5. 当前未完成 / 可继续

1. **启动任务**已接入 `WeiboTaskRunner`（签到 / 浏览 / 发帖 + 收尾超 LIKE），需按任务逐个真机调试  
2. 发帖：同步到微博勾选、输入框定位因机型可能需微调  
3. 头像真实抓取（目前多为 null）  
4. 浏览节奏 / 进帖子策略可按真机反馈再调  
5. containerid 是否随账号变化（deep link 已非主路径）

---

## 6. 常用命令

```bash
# 编译并安装到手机（用户要求默认用这个）
./gradlew installDebug

# 看设备
adb devices

# 抓超 LIKE / 导航日志
adb logcat -s WeiboNavigator:D WeiboDump:W WeiboAccessibilityService:D Timber:I
```

---

## 7. 用户偏好（已写入 memory）

- 每次编译验证默认：`./gradlew installDebug`
- 模块策略：改造现有 `app`，不新建 weibo 模块
- 包名：`cn.vove7.weibo.auto`

---

## 8. 一句话续作提示

> 微博助手 Compose 骨架已可用；刷新账号与超 LIKE 检测已接无障碍。  
> 打开超 LIKE 应用 `profile_allbadge` deep link + AccessibilityService Context，**不要 home、不要 app 内 am start**。  
> 下一步优先真机验证完整超like；若仍打不开，改为微博内点击导航。
