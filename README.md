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
