# MixinLib

取消了Starsector对反射的禁用，提供Mixin框架

### 安装

1. 解压，将`MixinLib`文件夹放入`Starsector/mods/`
2. 运行`install.bat`
3. 正常启动 Starsector

### 卸载

运行 `uninstall.bat`

## 开发

### 1. 声明依赖

`mod_info.json`：
```json
{
    "dependencies": [
        { "id": "MixinLib", "name": "MixinLib" }
    ],
}
```

### 2. 添加 Mixin 配置

在 Mod jar 根目录创建 `xxx.mixin.json`

`your.mixin.json`：
```json
{
    "required": true,
    "minVersion": "0.8",
    "package": "your.mixin.package",
    "compatibilityLevel": "JAVA_8",
    "mixins": [
        "YourMixinClass"
    ]
}
```

### Gradle 依赖

```groovy
repositories {
    maven { url = uri("https://repo.spongepowered.org/maven/") }
}

dependencies {
    compileOnly('org.spongepowered:mixin:0.8.7')
}
```