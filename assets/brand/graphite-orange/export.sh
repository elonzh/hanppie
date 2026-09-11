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

sips -s format png -z 512 512 "$source_dir/app-icon-master.png" \
  --out "$desktop_dir/hanppie.png" >/dev/null

temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/hanppie-assets.XXXXXX")
iconset_dir="$temporary_dir/Hanppie.iconset"
mkdir -p "$iconset_dir"
trap 'rm -r "$temporary_dir"' EXIT HUP INT TERM
for entry in 16:icon_16x16.png 32:icon_16x16@2x.png 32:icon_32x32.png 64:icon_32x32@2x.png 128:icon_128x128.png 256:icon_128x128@2x.png 256:icon_256x256.png 512:icon_256x256@2x.png 512:icon_512x512.png 1024:icon_512x512@2x.png; do
  size=${entry%%:*}
  file=${entry##*:}
  sips -s format png -z "$size" "$size" "$source_dir/app-icon-master.png" \
    --out "$iconset_dir/$file" >/dev/null
done
iconutil -c icns "$iconset_dir" -o "$desktop_dir/hanppie.icns"
ffmpeg -loglevel error -y -i "$source_dir/app-icon-master.png" \
  -filter_complex "[0:v]split=6[s16][s24][s32][s48][s64][s256];[s16]scale=16:16[o16];[s24]scale=24:24[o24];[s32]scale=32:32[o32];[s48]scale=48:48[o48];[s64]scale=64:64[o64];[s256]scale=256:256[o256]" \
  -map "[o16]" -map "[o24]" -map "[o32]" -map "[o48]" -map "[o64]" -map "[o256]" \
  -c:v bmp "$desktop_dir/hanppie.ico"

printf '%s\n' "Exported Hanppie Graphite Orange assets."
