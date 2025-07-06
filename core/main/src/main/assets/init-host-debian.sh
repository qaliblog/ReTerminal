#!/system/bin/sh
# This script is now intended to be directly executable by TerminalSession

DEBIAN_DIR=$PREFIX/local/debian

mkdir -p $DEBIAN_DIR

# More robust check for extraction: check for a key file like /bin/bash.
# Also, ensure the tarball actually exists before trying to extract.
if [ -f "$PREFIX/files/debian-rootfs.tar.gz" ]; then
    if [ ! -f "$DEBIAN_DIR/bin/bash" ]; then
        echo "Debian rootfs not found or incomplete, extracting..."
        # Clear out the directory before extracting to ensure a clean state,
        # but preserve tmp and root if they exist and have special permissions/content.
        # A simpler approach for now: just extract. If issues persist, explore partial cleanup.
        tar -xf "$PREFIX/files/debian-rootfs.tar.gz" -C "$DEBIAN_DIR"
    else
        echo "Debian rootfs already extracted."
    fi
else
    echo "ERROR: $PREFIX/files/debian-rootfs.tar.gz not found!"
    # Optionally, exit here if this is a fatal error.
    # exit 1
fi

# Ensure proot and libtalloc are in place and executable
# Copy from $PREFIX/files (where MkSession places them) to $PREFIX/local/bin and $PREFIX/local/lib
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
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

$LINKER $PREFIX/local/bin/proot $ARGS sh $PREFIX/local/bin/init-debian "$@"
