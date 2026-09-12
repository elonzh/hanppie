#!/bin/sh
set -eu

asset_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$asset_dir/../../.." && pwd)
source_dir="$asset_dir/source"
compose_dir="$project_dir/shared/src/commonMain/composeResources/drawable"
android_dir="$project_dir/androidApp/src/main/res"
desktop_dir="$project_dir/desktopApp/src/main/resources/icons"

command -v ffmpeg >/dev/null
command -v sips >/dev/null
command -v iconutil >/dev/null

mkdir -p "$compose_dir" "$desktop_dir"

ffmpeg -loglevel error -y -i "$source_dir/hanppie-companion-master.png" \
  -vf "scale=512:512:force_original_aspect_ratio=decrease,format=rgba" \
  "$compose_dir/hanppie_companion.png"

cp "$source_dir"/icons/*.svg "$compose_dir/"

cp "$source_dir/hanppie-mark.svg" "$compose_dir/hanppie_mark.svg"
for state in standby active recording talking; do
  cp "$source_dir/hanppie-expression-$state.svg" "$compose_dir/hanppie_expression_$state.svg"
done

for density_and_size in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  density=${density_and_size%%:*}
  size=${density_and_size##*:}
  mkdir -p "$android_dir/mipmap-$density"
  sips -s format png -z "$size" "$size" "$source_dir/app-icon-master.png" \
    --out "$android_dir/mipmap-$density/ic_launcher.png" >/dev/null
done

for density_and_size in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
  density=${density_and_size%%:*}
  size=${density_and_size##*:}
  content=$((size * 78 / 100))
  mkdir -p "$android_dir/mipmap-$density"
  ffmpeg -loglevel error -y -i "$source_dir/hanppie-companion-master.png" \
    -vf "scale=$content:$content:force_original_aspect_ratio=decrease,pad=$size:$size:($size-iw)/2:($size-ih)/2:color=0x00000000,format=rgba" \
    "$android_dir/mipmap-$density/ic_launcher_foreground.png"
done

sips -s format png -z 512 512 "$source_dir/app-icon-macos.png" \
  --out "$desktop_dir/hanppie.png" >/dev/null

cp "$desktop_dir/hanppie.png" "$compose_dir/hanppie_app_icon.png"

temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/hanppie-assets.XXXXXX")
iconset_dir="$temporary_dir/Hanppie.iconset"
mkdir -p "$iconset_dir"
trap 'rm -r "$temporary_dir"' EXIT HUP INT TERM
for entry in 16:icon_16x16.png 32:icon_16x16@2x.png 32:icon_32x32.png 64:icon_32x32@2x.png 128:icon_128x128.png 256:icon_128x128@2x.png 256:icon_256x256.png 512:icon_256x256@2x.png 512:icon_512x512.png 1024:icon_512x512@2x.png; do
  size=${entry%%:*}
  file=${entry##*:}
  sips -s format png -z "$size" "$size" "$source_dir/app-icon-macos.png" \
    --out "$iconset_dir/$file" >/dev/null
done
iconutil -c icns "$iconset_dir" -o "$desktop_dir/hanppie.icns"
ffmpeg -loglevel error -y -i "$source_dir/app-icon-master.png" \
  -filter_complex "[0:v]split=6[s16][s24][s32][s48][s64][s256];[s16]scale=16:16[o16];[s24]scale=24:24[o24];[s32]scale=32:32[o32];[s48]scale=48:48[o48];[s64]scale=64:64[o64];[s256]scale=256:256[o256]" \
  -map "[o16]" -map "[o24]" -map "[o32]" -map "[o48]" -map "[o64]" -map "[o256]" \
  -c:v bmp "$desktop_dir/hanppie.ico"

printf '%s\n' "Exported Hanppie Graphite Orange assets."

# The atlas embeds the same source assets; no duplicated artwork or design tokens.
cat > "$asset_dir/preview.html" <<'HTML'
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Hanppie · Graphite Orange 素材</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f3f5f7;color:#20242b;font:15px/1.6 system-ui,sans-serif}main{max-width:1100px;margin:auto;padding:40px 24px}header{display:flex;align-items:center;justify-content:space-between;gap:20px}h1{font-size:30px;line-height:1.2}h2{margin-top:36px;font-size:20px}a{color:#713016}section{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:16px}figure{margin:0;background:white;border-radius:20px;padding:24px;text-align:center}figure img{width:120px;height:120px;object-fit:contain}figcaption{font-size:12px;color:#5c6670;overflow-wrap:anywhere;margin-top:14px}.symbols figure{background:#1c232b}.symbols img{width:32px;height:32px}.symbols figcaption{color:#b7c0c8}.expressions img{width:100%;height:64px}.identity{grid-template-columns:repeat(auto-fit,minmax(200px,1fr))}.app{border-radius:24px}small{color:#5c6670}
</style><main><header><div><small>HANPPIE / GRAPHITE ORANGE</small><h1>设计元素 · 程序素材</h1></div><a href="../../../DESIGN.md">完整设计规范 ↗</a></header>
<p>直接引用导出母版。程序以相同 SVG 进行语义着色；应用图标下方另列 32px、64px 预览。</p>
<section class="identity"><figure><img class="app" src="source/app-icon-master.png"><figcaption>通用应用图标母版</figcaption></figure><figure><img src="source/app-icon-macos.png"><figcaption>macOS · 透明留白圆角母版</figcaption></figure><figure><img src="source/hanppie-companion-master.png"><figcaption>透明伙伴头像</figcaption></figure><figure><img src="source/hanppie-mark.svg"><figcaption>导航小标记</figcaption></figure><figure><img class="app" style="width:32px;height:32px" src="source/app-icon-macos.png"> <img class="app" style="width:64px;height:64px" src="source/app-icon-macos.png"><figcaption>32 / 64 px</figcaption></figure></section>
<h2>功能图标</h2><section class="symbols">
HTML
for svg in "$source_dir"/icons/*.svg; do
  name=$(basename "$svg")
  printf '<figure><img src="source/icons/%s"><figcaption>%s</figcaption></figure>\n' "$name" "$name" >> "$asset_dir/preview.html"
done
printf '%s\n' '</section><h2>点阵状态</h2><section class="expressions symbols">' >> "$asset_dir/preview.html"
for state in standby active recording talking; do
  printf '<figure><img src="source/hanppie-expression-%s.svg"><figcaption>%s</figcaption></figure>\n' "$state" "$state" >> "$asset_dir/preview.html"
done
printf '%s\n' '</section><p><small>生成方式及平台资源目录见 manifest.json。应用界面截图单独保存在本次验证记录中。</small></p></main></html>' >> "$asset_dir/preview.html"
