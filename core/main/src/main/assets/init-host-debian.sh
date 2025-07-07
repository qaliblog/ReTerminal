#!/system/bin/sh
# This script is now intended to be directly executable by TerminalSession

DEBIAN_DIR=$PREFIX/local/debian

mkdir -p $DEBIAN_DIR

# Ensure proot and libtalloc are in place and executable
# Copy from $PREFIX/files (where MkSession places them) to $PREFIX/local/bin and $PREFIX/local/lib
# Rootfs extraction is now handled by the application code.
if [ -f "$PREFIX/files/proot" ]; then
    cp "$PREFIX/files/proot" "$PREFIX/local/bin/proot"
    chmod 755 "$PREFIX/local/bin/proot"
else
    echo "ERROR: $PREFIX/files/proot not found!"
fi

for sofile in "$PREFIX/files/"*.so.2; do
    dest="$PREFIX/local/lib/$(basename "$sofile")"
    [ ! -e "$dest" ] && cp "$sofile" "$dest"
done


ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b $PREFIX/local/stat:/proc/stat"
ARGS="$ARGS -b $PREFIX/local/vmstat:/proc/vmstat"

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi


ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b /sys"

if [ ! -d "$PREFIX/local/debian/tmp" ]; then
 mkdir -p "$PREFIX/local/debian/tmp"
 chmod 1777 "$PREFIX/local/debian/tmp"
fi
ARGS="$ARGS -b $PREFIX/local/debian/tmp:/dev/shm"

ARGS="$ARGS -r $PREFIX/local/debian"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
# ARGS="$ARGS --sysvipc" # Removed as it's an unknown option for the current proot
ARGS="$ARGS -L"

$LINKER $PREFIX/local/bin/proot $ARGS $PREFIX/local/bin/init-debian "$@"
