# 微博助手 / Android-Auto-Api

基于 Android 无障碍服务的微博自动化助手。这个仓库最初是无障碍 API 示例，现在已经改造成一个可直接安装使用的微博辅助应用。

## 项目定位

- 通过无障碍服务操作微博
- 使用 Jetpack Compose + Material3 构建界面
- 使用 Room 保存账号、任务记录、模板文案和执行日志
- 不收集密码，只依赖系统无障碍权限和微博已登录状态

## 主要功能

- 刷新微博账号并入库
- 多账号选择与切换
- 超 LIKE 检测
- 任务执行：签到、浏览、发帖、结束后检测超 LIKE
- 模板管理：发帖模板、评论模板
- 远程更新文案
- 任务日志查看
- 自动更新 APK
- 悬浮窗任务控制
- 参数配置：评论上限、浏览停留时长、浏览滑动次数、水贴条数

## 浏览逻辑

- 浏览任务先按当前配置执行一轮
- 任务结束后会读取当日“看帖 / 评论 / 转发 / 签到”完成情况
- 如果“看帖”未完成，不会整轮重跑，而是按缺口补滑
- 补滑规则：缺 1 组补 10 次滑动
- 例如：
  - 看帖 `1/4`，还差 3 组，补滑 30 次
  - 看帖 `3/4`，还差 1 组，补滑 10 次

## 浏览任务详细执行流程（按当前代码）

浏览任务的完整链路如下：

```text
选择账号 / 任务
  -> TaskRunner 按账号串行执行
  -> 切换微博账号
  -> 进入「赵今麦」超话并自动签到
  -> 按配置滑动浏览
  -> 读取微博每日任务进度
  -> 看帖未完成则按缺口补滑
  -> 当前账号结束后检测超 LIKE
```

### 1. 任务入口与执行顺序

点击“启动”后，`DashboardViewModel` 会检查无障碍服务、账号选择和当前是否已有任务运行。确认任务后，调用 [TaskRunner.kt](app/src/main/java/cn/vove7/weibo/auto/domain/task/TaskRunner.kt) 执行任务。

任务会按固定顺序执行：

```text
浏览 -> 发帖 -> 超 LIKE
```

签到不是独立任务。只要选择了浏览或发帖，进入目标超话时就会自动检查并签到。

每个账号执行结束后都会检测超 LIKE，即使任务选择框中没有单独勾选“检测超 LIKE”。

### 2. 切号与进入目标超话

每个账号的执行过程是：

1. 进入微博“我”->“设置”->“账号管理”。
2. 点击指定昵称完成切号。
3. 如果选择了浏览或发帖，回到微博首页后进入目标超话。
4. 进入“我”页面并上滑露出“超话社区”。
5. 优先直接点击“赵今麦”超话；找不到时进入超话社区，再尝试“全部关注”和目标超话。
6. 通过 `SGPage` / `SuperGroup` 页面特征确认确实进入了超话详情页。
7. 读取“连签”天数；如果检测到“签到”按钮，则自动执行签到。

相关代码在 [WeiboNavigator.kt](app/src/main/java/cn/vove7/weibo/auto/domain/weibo/WeiboNavigator.kt) 的 `openTargetSuperTopic` 和 `enterMeThenOpenTopic` 中。

### 3. 浏览第一轮

浏览任务会先读取当前自动化配置，然后完整执行一轮配置的滑动，不会在第一轮之前根据今日进度跳过浏览。

配置定义在 [AutomationSettingsRepository.kt](app/src/main/java/cn/vove7/weibo/auto/data/repo/AutomationSettingsRepository.kt)：

- 默认滑动次数：40 次。
- 默认每次停留：6 秒。
- 每日评论上限：默认 4 条。
- 滑动次数可配置为 1 到 200 次。
- 停留时间可配置为 1 到 30 秒。

每轮浏览由 `WeiboNavigator.browseSuperTopicPosts` 执行：

1. 确认当前仍在“赵今麦”超话。
2. 点击“最新”标签。
3. 循环执行指定次数：从屏幕下方区域向上滑动，滑动距离和持续时间在几种模式间变化。
4. 每次滑动后停留配置的时间，模拟阅读。
5. 每次滑动前再次检查页面是否仍是目标超话；如果页面跑偏，则返回微博首页并重新进入超话。

### 4. 浏览中的评论

浏览过程中会穿插评论，触发条件是：

```text
当前评论数未达到配置上限
且不是最后一次滑动
且当前滑动序号为奇数
且存在评论模板
```

评论模板由 [CommentTemplateRepository.kt](app/src/main/java/cn/vove7/weibo/auto/data/repo/CommentTemplateRepository.kt) 随机选择。实际实现会从本地全部评论模板中随机取一条。

评论操作流程是：

1. 在当前可见区域查找 `contentTextView`。
2. 要求正文包含“赵今麦”。
3. 随机选择一条正文并长按正文中部。
4. 点击弹出菜单中的“评论”。
5. 找到输入框并填入评论模板。
6. 如果“同时转发”处于勾选状态，先取消勾选。
7. 点击发送。
8. 等待评论编辑页关闭并回到超话页面。
9. 只有确认发送成功后，才记录一条评论成功记录。

每条成功评论会写入 `task_records`，并用于计算当天本应用已经成功评论的数量。相关计数逻辑在 [TaskRepository.kt](app/src/main/java/cn/vove7/weibo/auto/data/repo/TaskRepository.kt) 中。

### 5. 读取每日任务进度

一轮浏览完成后，程序会打开超话底部的“我的”页面，再打开每日任务面板，读取：

- 签到。
- 看帖。
- 评论帖子。

读取逻辑在 [WeiboNavigator.kt](app/src/main/java/cn/vove7/weibo/auto/domain/weibo/WeiboNavigator.kt) 的 `inspectDailyTaskProgress` 和 `readDailyTaskProgress` 中。

程序会根据任务标题，扫描标题下方约 180 像素范围内的文本，并匹配类似下面的进度文案：

```text
今日完成次数 3/4
```

读取后的结果会保存到账号表，包括本地自然日、签到状态、看帖完成数 / 目标数和评论完成数 / 目标数，供首页账号卡片展示。

### 6. 看帖缺口补滑

浏览任务第一次检查进度后，如果看帖未完成，就计算缺口：

```text
缺少组数 = max(目标次数 - 已完成次数, 0)
补滑次数 = 缺少组数 * 10
```

例如：

```text
看帖 1/4 -> 缺 3 组 -> 补滑 30 次
看帖 3/4 -> 缺 1 组 -> 补滑 10 次
看帖 4/4 -> 不补滑
```

每次补滑后都会重新读取每日任务进度，最多补 3 轮。如果任务进度读取失败或没有有效目标次数，则跳过补滑；如果达到 3 轮后仍未完成，会在日志中提示已达到补滑上限。

缺口补滑逻辑位于 [TaskRunner.kt](app/src/main/java/cn/vove7/weibo/auto/domain/task/TaskRunner.kt) 的 `runOneTask` 和 `missingBrowseGroupCount` 中。

### 7. 浏览异常恢复

如果浏览过程中发生页面跳转、超话页面丢失或导航异常，程序会尝试恢复：

1. 返回微博首页。
2. 重新进入目标超话。
3. 重新读取当前每日任务进度。
4. 根据最新缺口重新计算需要滑动的次数。
5. 最多重试 3 次。

如果恢复后发现看帖已经完成，则直接结束浏览；如果仍有缺口，则按新的缺口继续浏览。

相关代码是 `TaskRunner.runBrowsePassWithRecovery`。

### 8. 任务记录与当前实现注意事项

- 浏览开始时写入 `BROWSE/RUNNING`，结束后写入成功或失败记录。
- 每条成功评论单独写入 `COMMENT/SUCCESS`。
- 每次读取每日任务进度都会更新账号当天的任务状态。
- README 中提到的“转发”进度目前没有被 `DailyTaskProgress` 读取，数据库中的转发字段只是历史兼容字段。
- 每日任务检测发生在浏览任务结束后，不是所有任务全部结束后。
- 即使补滑 3 轮后仍未达到看帖目标，当前代码仍会写入浏览成功记录，只会在进度日志中提示补滑轮次已用尽。
- `BROWSE_DURATION_MS = 120` 秒目前没有参与实际控制，真实耗时由滑动次数、停留时间和评论操作共同决定。默认 40 次、每次停留 6 秒时，仅停留时间就约 240 秒。
- 目标超话和评论候选正文目前都固定依赖“赵今麦”。

## 远程文案更新

模板管理页右上角提供“更新文案”按钮，会从远程地址下载 JSON 并覆盖本地模板。

- 地址：`https://file.qingzhou.link/yaozechuan/comment.json`
- 格式：

```json
{
  "fatie": ["发帖模板1", "发帖模板2"],
  "pinglun": ["评论模板1", "评论模板2"]
}
```

- `fatie` 会更新本地发帖模板
- `pinglun` 会更新本地评论模板
- 空字符串会被过滤，重复内容会去重

## 项目结构

```text
app/
  src/main/java/cn/vove7/weibo/auto/
    MainActivity.kt
    WeiboApp.kt
    data/        # Room 实体、DAO、仓库、更新逻辑
    domain/      # 微博导航、任务执行、业务逻辑
    service/     # 无障碍服务、保活服务
    ui/          # Compose 页面
accessibility/   # 无障碍 API 封装
core/            # 视图搜索、手势、导航等基础能力
uiauto/          # UIAutomator 相关辅助
```

核心代码可以优先看这几个文件：

- [WeiboApp.kt](app/src/main/java/cn/vove7/weibo/auto/WeiboApp.kt)
- [DashboardScreen.kt](app/src/main/java/cn/vove7/weibo/auto/ui/dashboard/DashboardScreen.kt)
- [DashboardViewModel.kt](app/src/main/java/cn/vove7/weibo/auto/ui/dashboard/DashboardViewModel.kt)
- [TaskRunner.kt](app/src/main/java/cn/vove7/weibo/auto/domain/task/TaskRunner.kt)
- [WeiboNavigator.kt](app/src/main/java/cn/vove7/weibo/auto/domain/weibo/WeiboNavigator.kt)

## 运行前准备

1. 安装微博并登录账号
2. 打开本应用
3. 开启无障碍权限
4. 如需悬浮控制，开启悬浮窗权限
5. 点“刷新”拉取已登录账号
6. 进入“模板管理”补充或更新发帖 / 评论文案
7. 按需要调整“参数配置”

## 构建

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew installDebug
```

## 关键说明

- `app` 是当前主应用模块
- `accessibility`、`core`、`uiauto` 仍然保留，提供底层能力
- 首次启动时，若本地没有模板，会写入默认文案
- 任务执行依赖微博页面结构，真机上可能需要根据版本微调

## 许可证

见 [LICENSE](LICENSE)
