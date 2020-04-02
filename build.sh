export GOOS=android
export GOARCH=arm
export GOARM=7
export CGO_ENABLED=1
export CC="/usr/lib/android-ndk/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-gcc"
export CXX="/usr/lib/android-ndk/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-g++"
export LD="/usr/lib/android-ndk/toolchains/arm-linux-androideabi-4.9/prebuilt/linux-x86_64/bin/arm-linux-androideabi-ld"

ANDROID_SYSROOT="/usr/lib/android-ndk/platforms/android-19/arch-arm"
export LDFLAGS="--sysroot=${ANDROID_SYSROOT}"
export CGO_CFLAGS="--sysroot=${ANDROID_SYSROOT}"
export CGO_LDFLAGS="--sysroot=${ANDROID_SYSROOT}"
go build -x -v -o dist/hanppie cmd/hanppie/main.go
