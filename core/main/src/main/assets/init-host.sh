ALPINE_DIR=$PREFIX/local/alpine

mkdir -p $ALPINE_DIR

if [ -z "$(ls -A "$ALPINE_DIR" | grep -vE '^(root|tmp)$')" ]; then
    if [ -f "$PREFIX/files/alpine.tar.gz" ]; then
        tar -xf "$PREFIX/files/alpine.tar.gz" -C "$ALPINE_DIR"
    else
        echo "Error: alpine.tar.gz not found at $PREFIX/files/alpine.tar.gz"
        echo "Please ensure the Alpine Linux rootfs is properly downloaded."
        exit 1
    fi
fi

# Ensure required directories exist
mkdir -p "$PREFIX/local/bin"
mkdir -p "$PREFIX/local/lib"

# Copy proot if it exists
if [ ! -e "$PREFIX/local/bin/proot" ]; then
    if [ -f "$PREFIX/files/proot" ]; then
        cp "$PREFIX/files/proot" "$PREFIX/local/bin"
        chmod +x "$PREFIX/local/bin/proot"
    else
        echo "Error: proot not found at $PREFIX/files/proot"
        echo "Please ensure proot is properly downloaded."
        exit 1
    fi
fi

# Copy library files
for sofile in "$PREFIX/files/"*.so.2; do
    if [ -f "$sofile" ]; then
        dest="$PREFIX/local/lib/$(basename "$sofile")"
        [ ! -e "$dest" ] && cp "$sofile" "$dest"
    fi
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

if [ ! -d "$PREFIX/local/alpine/tmp" ]; then
 mkdir -p "$PREFIX/local/alpine/tmp"
 chmod 1777 "$PREFIX/local/alpine/tmp"
fi
ARGS="$ARGS -b $PREFIX/local/alpine/tmp:/dev/shm"

ARGS="$ARGS -r $PREFIX/local/alpine"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

$LINKER $PREFIX/local/bin/proot $ARGS sh $PREFIX/local/bin/init "$@"
