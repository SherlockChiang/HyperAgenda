# LSPosed 模块仓库申请

目标仓库：`Xposed-Modules-Repo/io.github.sherlockchiang.hyperagenda`
提交入口：<https://modules.lsposed.org/submission>
官方说明：<https://github.com/Xposed-Modules-Repo/submission>

## 包名与版本

- 包名：`io.github.sherlockchiang.hyperagenda`，对应 GitHub 用户 `SherlockChiang`。
- 版本：0.6.2 / versionCode 22。
- Release 标签：`22-0.6.2`。
- APK 继续使用 0.6.1 的签名密钥；官方要求没有规定必须更换密钥。
- 原包名 `com.vince.hyperagenda` 与新版是两个独立应用，设置不会自动迁移。安装时先停用旧模块、启用新模块并重启系统界面，避免重复注入。

## 官方四条要求

1. 官方模块仓库名与 APK 包名一致。
2. 仓库 description 非空，填写模块名。
3. 至少有一个合法 Release。
4. Release 包含 APK 附件，标签为 `VersionCode-VersionName`。

来源：<https://github.com/Xposed-Modules-Repo/.github/blob/main/profile/README.md>

## 申请内容

标题：`[submission] io.github.sherlockchiang.hyperagenda`

正文草稿：

> Module name: HyperAgenda / 锁屏日程
>
> Application ID: io.github.sherlockchiang.hyperagenda
>
> Author: @SherlockChiang
>
> Source: https://github.com/SherlockChiang/HyperAgenda
>
> A traditional Xposed / LSPosed module for Xiaomi HyperOS 4. Displays upcoming local calendar events below the lockscreen clock, with relative time, calendar filtering and privacy controls. Scope: com.android.systemui.
>
> The application ID uses my GitHub username prefix. Please create the module distribution repository and invite @SherlockChiang.

发布完成后再在申请正文中添加实际 Release 链接；草稿不代表已经提交申请。

## 发布前

1. 核对 `xposedmodule`、`xposeddescription`、`xposedminversion`（93）、`xposedscope` 与 Xposed 入口。
2. 保留现有签名私钥并备份。证书 SHA-256：`595e889f97bd7f1a810ba95aa5be5677f4d7e18986e7d4eb37a40340a310a834`。
3. 提交并推送待发布源码后运行 `.\scripts\publish-release.ps1`；该脚本将校验签名并上传 APK 与 SHA-256 文件。

## 申请通过后

1. 接受机器人发来的官方模块仓库邀请。
2. 在 GitHub **网页**设置仓库 description 为「锁屏日程（HyperAgenda）」、homepage 为源码仓库地址。维护者角色的 API 权限可能不支持修改仓库描述。
3. 补充 README.md、SUMMARY、SCOPE、SOURCE_URL；官方模块仓库用于说明与分发，不上传源码。
4. 重新运行发布脚本，向官方模块仓库发布 `22-0.6.2` 与 APK 附件。
5. 核对模块仓库的包名、description、Release 标签及附件；索引通常需要 5–15 分钟。

## 发布约定

- 新建 Release 时同时上传 APK，不能先发布空 Release 再单独上传附件。
- 仅替换附件不会触发机器人，还需要修改 Release 内容。
- 保持签名密钥一致；不要为上架重新运行 `setup-release-signing.ps1` 生成新密钥。
- 后续更新递增 versionCode，按 `VersionCode-VersionName` 发布。
