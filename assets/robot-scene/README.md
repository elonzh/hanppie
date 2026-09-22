# RoboMaster 展示模型

当前模型使用已解包的 RoboMaster App **1.1.5** 的原始 OBJ 网格、UV、Transform 装配
与 Material/Texture2D。模型及纹理来源于官方 App，并非 Hanppie 原创；转换后的 GLB 与使用的图标随代码通过 Git LFS 管理。

转换器固定对应该版本 `resources` 导出：S1 使用 `xw0607` 层级（Transform 18392），
EP 使用 `EP_01A`（20795）。S1 编辑器预制体的占位材质通过共享网格 ID 复用 EP 底盘材质，
云台材质对应 `LAB 3/xw0607_less` 的绑定。保留每个 OBJ 子网格的独立材质槽，
不再将官方纹理按部件名称套到另一套 ROS 网格的 UV 上。

UnityPy OBJ 已翻转 X 坐标和面序，Transform 仍使用 Unity 坐标；转换器显式处理两者，
最终 Y 向上、Z 向前，展示比例为 3。云台使用原始预制体的 yaw/pitch 转轴位置。
模型是外观素材，不代表经过本项目标定的 CAD、碰撞、惯性或物理装配模型。

环境反射复用 Google Filament **v1.76.0** 分发包中的 `lightroom_14b_ibl.ktx`，
其[环境素材目录](https://github.com/google/filament/tree/v1.76.0/third_party/environments)
提供 CC0 及 HDRI Haven 来源记录；原文保留在 [LICENSE.environment.html](LICENSE.environment.html)。
车库来自同一 App 的 `LAB 3/StaticMesh` 原始几何、材质与 Transform，随机器人一起遵守上述本地素材边界。

## 再生成

首页状态图标也复用同一 App 的 Texture2D 导出，位于 Compose `drawable/official_*.png`。
来源对应如下：

| 本地资源 | 原始 Texture2D |
| --- | --- |
| official_connection_router | resources_1704_ic_Topbar_remote_router |
| official_connection_direct | resources_1234_ic_Topbar_remote_nor |
| official_connection_generic | resources_1050_ic_connected_2x |
| official_battery_full | resources_1567_ic-common-battery-full |
| official_battery_mid | resources_1963_ic-common-battery-mid |
| official_battery_low | resources_1790_ic-common-battery-low |
| official_battery_empty | resources_1653_ic-common-battery-empty |
| official_ammo_infrared | resources_853_ic-FPV-virtualattack_standard |
| official_ammo_gel | resources_2064_ic-FPV-shooting-standard |

连接方式按当前地址匹配已记录的连接方式，未知方式使用通用连接图标，不根据 IP 猜测。
电量图标采用官方档位，精确数值仍显示遥测百分比。

```bash
uv run assets/robot-scene/generate.py --app-assets /path/to/local/app-analysis/assets
```

输出为 `shared/src/commonMain/composeResources/files/models/robot-{s1,ep,base}.glb` 和 `workshop.glb`。
构建使用生成 GLB，不依赖 Python 或 ROS。保留原始 UV、颜色、法线、粗糙度、金属度、AO 与
透明模式；Unity DXT5nm 的 alpha/green 解码为 RGB 法线，粗糙度和金属度打包到 glTF 的 G/B。
禁用部件与碰撞网格不导入。车库独立转换，保留原始地面、纹理重复比例与物件摆放；以原场景机器人位置和朝向对齐展示模型。

装甲与后部中控盖使用 `KHR_materials_transmission`，透射系数分别为 0.45 与 0.85，
装甲以非金属烟黑塑料渲染，粗糙度系数 0.75、法线强度 0.6；颜色乘数为 `(1, 1, 1)`，
保留原本已偏暗的颜色贴图，避免重复压暗并丢失壳体细节。透明中控盖移除不透明底盘颜色贴图，采用浅蓝灰
透射色与 0.15 粗糙度系数。Filament 显式启用屏幕空间透射，以看见壳体后面的实际模型部件。
对照依据为用户手机上的官方 App 首页，以及
[DJI 装甲套件照片](https://store.dji.com/es/product/robomaster-s1-chassis-armor-kit)。
材质转换是视觉近似，不是测量得到的透射率；采用薄表面透射，不模拟体积厚度和光谱色散。
扬声器连接线 `sound_low` 使用黑色绝缘材质，避免错误复用银色 TOP 图集。保留原始贴图细节；透明外壳不再使用容易产生重叠三角形斑块的 alpha coverage 混合。

## 三维环境

转换器恢复 `LAB 3/StaticMesh`（Transform 20308），以 `xw0607_less`（20576）为世界锚点。
同材质几何合并以减少绘制调用。静态合批清空 MeshFilter 时，仅从同名且唯一的原始预制体绑定恢复网格；
无法恢复的项目明确输出到转换日志，不生成替代模型。Unity 专用灯光光束与烘焙阴影平面不导入。

保留原场景的 935 个几何部件，另复用原始货架、托盘和纸箱的网格、UV 与材质，
在展示模型附近布置 5 个物件实例（8 个子网格部件），合计 943 个部件后按材质合并。
近景物件是为首页构图重新摆放的展示区，不代表官方场景原位复刻；环境物件参与实际投影。

`studio-ibl.ktx` 复制自 Filament 分发包 `bin/assets/ibl/lightroom_14b/lightroom_14b_ibl.ktx`。
Filament 环境反射、方向光、点光源、AO 和 PCSS 阴影替代原引擎照明。地面是实际带纹理的三维网格，
覆盖整个视口并接收模型投影；不使用边缘渐隐平面或背景截图。
原 Unity 自定义反射着色器、烘焙光照及未导出的合批网格没有完整恢复，因此不能声称像素级复刻。

`robot-s1.glb` 的姿态查询动画先为 `yaw`、`pitch`，再为右前、左前、左后、右后四轮；
EP/基础底盘只有四轮通道。均不自动播放：

| 通道 | 输入范围 | 采样时间 | 展示正方向 |
| --- | --- | --- | --- |
| yaw | −360° 至 +360° | `(角度 + 360) / 90` 秒 | 向机器人右侧 |
| pitch | −180° 至 +180° | `(角度 + 180) / 90` 秒 | 抬头 |

设备识别、遥测来源与未验证边界见 [当前架构](../../docs/architecture.md)。

四轮通道输入为 `[0, 360]` 度，采样时间为 `角度 / 90` 秒。左右电机符号在共享状态映射处统一，
轮子转轴来自原始装配 Transform；不把旋转示意当作轮胎接地、滑移或里程计标定。
