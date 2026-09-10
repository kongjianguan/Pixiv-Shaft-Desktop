# 构建与打包

[English](build-and-package.md) | 中文

使用以下流程验证本地构建并生成 macOS DMG。

## 1. 选择 JDK 21

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

验证当前选择的运行时：

```bash
java -version
```

## 2. 编译与测试

```bash
./gradlew :app:compileKotlin
./gradlew test
```

可观察结果是 Kotlin 编译成功，随后 JUnit 测试运行成功。

## 3. 运行应用

```bash
./gradlew :app:run
```

应用应打开 Compose Desktop 窗口。未登录启动时显示 Pixiv 登录页；如果 Keychain 中存在有效 access token，则打开已认证的导航外壳。

## 4. 构建 DMG

```bash
./gradlew :app:packageDmg
```

确认 `app/build/compose/binaries/main/dmg/PixivShaft-1.0.0.dmg` 存在。打包配置会将 `java.sql` 和 `jdk.unsupported` 加入运行时映像。

## 5. 检查安装包

挂载 DMG，把 `PixivShaft.app` 复制到 `/Applications`，然后从 Finder 启动。分发安装包前，测试登录、一个 API feed、一个图片详情页和一次小型下载。
