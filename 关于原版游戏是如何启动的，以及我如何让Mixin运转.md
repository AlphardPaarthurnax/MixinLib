一、原版游戏

1. JVM 入口：StarfarerLauncher.main(String[])

显而易见不是吗？Starsector 的 JVM 参数保存在 vmparams 中。classpath 包含了 starfarer.api.jar、starfarer_obf.jar、Janino、LWJGL、Log4j 等等本体和核心依赖，然后它只是一味地启动StarfarerLauncher

StarfarerLauncher(boolean)要做的就很多了
初始化日志
初始化资源路径
调用 ModManager.getInstance() 建立 Mod 管理器
调用 StarfarerSettings.öÔ0000() 读取游戏设置
创建启动界面
等待玩家选择分辨率、全屏、声音以及启用Mod

[dark]以防你不知道，StarfarerSettings.ØÕ0000() 会检查系统属性 launchDirect 是否存在。如果存在，构造方法不再显示启动器，而是从 startRes、startFS 和 startSound 读取参数[/dark]

2.那Mod呢？

ModManager 是一个单例。
updateList() 会扫描 Mod 目录下的子目录，在找到 mod_info.json 后调用

ModManager.loadModSpec(File modDirectory, File modInfo)

把 id、name、version、dependencies、jars、modPlugin、totalConversion 等字段填入 ModManager.ModSpec。扫描结束后，ModManager.loadEnabledModList() 再读取用户保存的启用列表，得到 enabledMods。

3.接下来你按下了启动按钮

于是在actionPerformed(ActionEvent actionEvent)读取界面上的分辨率、全屏和声音选项,
然后创建一个线程，调用静态方法StarfarerLauncher.o00000(boolean fullscreen, boolean sound, String width, String height)
它会读取启用 Mod，并在末尾调用 CombatMain.main(String[] args)

StarfarerLauncher.o00000内的流程：
1.调用ModManager.getInstance().getEnabledMods()得到启用Mod的列表，然后按ModManager.ModSpec.getSortString()排序；若sortString为空，就退回ModManager.ModSpec.getName()。日志按正序打印优先级，真正挂载资源时则从列表尾部向前遍历，每个 Mod 的资源目录通过C.Ó00000().o00000(modSpec.getPath(), true, modSpec)加入原版资源系统（[dark]也就是说我们真的是按字母排序*资源和Plugin的顺序并不是同一个[/dark]）
2.每个 ModManager.ModSpec.getJars() 返回的是 mod_info.json 中声明的 JAR 相对路径，把它们拼成完整路径后加入：ScriptStore.Õ00000()返回的 JAR 路径列表。
3.启动游戏，CombatMain.main(String[] args)配置好环境后就会进入ResourceLoaderState、TitleScreenState、CampaignState、CombatState的状态机和渲染循环

4.那么，JAR与Janino呢？

Mod 代码主要在 ResourceLoaderState.init(Map) 中开始加载。和类加载有关的关键调用顺序是：
ScriptStore.ô00000()：建立原版脚本限制加载器
ScriptStore.Object(String)：登记 Mod Plugin 以及数据文件引用到的脚本类名
ScriptStore.int()：启动后台脚本加载线程
ScriptStore.ö00000()：扫描 data/scripts/plugins 和 data/scripts/world 下的松散 .java
ScriptStore.Ô00000()：等待后台加载结束并传播异常
ModManager.getEnabledModPlugins()：实例化启用 Mod 的 ModPlugin
ModPlugin.onApplicationLoad()：通知 Mod 游戏已完成应用级加载

二、那么MixinLib做了什么
我们需要两个JVM，但：

1.在那之前我们得先让MixinLib被启动

当然是 -javaagent ，且有 Premain-Class
因此在StarfarerLauncher.main()发生之前我们就先运行了AgentEntry.premain()
通过mixinlib.phase区分两个阶段：launcher和game

2.寸止

HandoffTransformer.transform()会定位启动方法并在游戏启动CombatMain.main()的前一刻
if (Handoff.beforeGame(fullscreen, sound, width, height)) {
return;
}
然后在beforeGame中，Handoff.capture()被调用，构造LaunchSnapshot，记录launcher要传递的信息：
工作目录 workingDirectory
MixinLib 自身 JAR 路径
分辨率、全屏和声音选项
Launcher JVM 的核心 classpath
最终启用 Mod 的数量及其声明的全部 JAR，保留原版挂载顺序
随后Handoff.startGameProcess(LaunchSnapshot)通过 ProcessBuilder 创建第二个 JVM
核心classpath和启用Mod的JAR路径通过--core-jar、--mod-jar命令行参数传递，分辨率、全屏和声音通过startRes、startFS、startSound系统属性传递。

3.Game JVM

在这个新进程的开始，GameBootstrap通过LaunchSnapshot.fromArguments()解析有序路径列表，工作目录从user.dir读取，MixinLib自身JAR路径从CodeSource获取。检查路径后建立TargetClassLoader、初始化Mixin，此时还没有加载游戏和Mod内容；标准输入输出继续继承原输入输出。系统classpath仍只有bridge，游戏和Mod JAR仍由TargetClassLoader加载。
随后阻止ScriptStore重复加载mod JAR、
实现对Janino动态加载的支持。
在一切准备工作完成后，要做的也只有，
重新启动原版：StarfarerLauncher.main(String[])
GameJVM 带有参数 -DlaunchDirect=true 因此会直接启动游戏

[dark]或许你可以这么认为：通过-javaagent我们得以劫持游戏启动器，在点下启动按钮时保存启动器所配置的"config"，随后我们启动我们自己的游戏启动器，并在其中启动游戏。（如果把“自己的游戏启动器”单独拿出来并添加UI，或许我们能够得到第三方启动器，啊啊，HMCL，PCL，你们在哪......）[/dark]
