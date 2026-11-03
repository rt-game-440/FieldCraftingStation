# 场域合成站（Field Crafting Station）

GTNH（GregTech: New Horizons）2.9.0 的附属 mod。添加一种特殊工作台：可以像普通工作台一样使用，同时能识别周边一定范围内所有容器里的物品，直接用于合成与取放（类似匠魂的合成站）。

- 五个电压等级：LV / MV / HV / EV / IV，识别半径 3 / 5 / 8 / 12 / 16 格。
- 需要持续耗电：30 / 120 / 480 / 1920 / 7680 EU/t，六个面都能接受 EU。断电时降级为普通工作台。
- 物品清单：汇总视图与分容器视图，支持搜索、滚动、取出与存入。
- 合成配方为 GT 组装机配方，详见 `docs/spec.md`。

规格见 [docs/spec.md](docs/spec.md)，术语见 [GLOSSARY.md](GLOSSARY.md)，架构决策见 `docs/adr/`。

## 构建与测试

需要 Java（构建使用 Gradle 守护进程，源码以 Java 8 API 为准，由 Jabel 提供语法特性）。

```powershell
.\gradlew.bat spotlessApply build     # 格式化 + 编译 + 单元测试
.\gradlew.bat test                    # 只跑单元测试
```

测试使用 JUnit 5 + 假容器，覆盖 `FieldInventory` 的汇总、取出、存入与合成消耗规则。

## 部署到本地 GTNH 实例

`scripts/deploy.ps1` 会构建 jar、删除实例里旧的 `fieldcrafting-*.jar`、复制新 jar 到实例的 mods 目录：

```powershell
.\scripts\deploy.ps1                                  # 构建（含测试）并部署
.\scripts\deploy.ps1 -SkipTests                       # 跳过测试，加快部署
.\scripts\deploy.ps1 -ModsDir "D:\other\.minecraft\mods"   # 部署到其他实例
```

默认 mods 目录为 `D:\mc\Prism-GTNH\instances\GTNH-2.9.0-RC-2\.minecraft\mods`。

## 占位贴图

`scripts/gen_textures.py` 用 Pillow 生成各档位的占位贴图（木质工作台底色 + 档位色带）：

```powershell
python scripts/gen_textures.py
```

## 开发约定

- modid `fieldcrafting`，包名 `com.rtgame.fieldcrafting`。
- 依赖（均为 compileOnly，来自 GTNH Maven）：GregTech、GTNHLib、NotEnoughItems、ModularUI2。
- 获取方式：本版本中只能通过组装机合成，或创造模式 / 指令获得。

## 许可

MIT，见 [LICENSE](LICENSE)。
