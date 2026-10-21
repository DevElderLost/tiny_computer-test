export DATA_DIR=@DATA_PATH@
export PATH=$DATA_DIR/bin:$PATH
export LD_LIBRARY_PATH=$DATA_DIR/lib
export CONTAINER_DIR=$DATA_DIR/containers/@CONTAINER_INDEX@
export PROOT_TMP_DIR=$DATA_DIR/proot_tmp
export PROOT_LOADER=$DATA_DIR/applib/libproot-loader.so
export PROOT_LOADER_32=$DATA_DIR/applib/libproot-loader32.so
export HOME=$DATA_DIR/home
export TMPDIR=$DATA_DIR/tmp
export TERM=xterm-256color
mkdir -p "$HOME"
cd "$HOME"
PS1='[tiny] $PWD $ '

# Masuk ke rootfs Linux (default: containers/@CONTAINER_INDEX@) sebagai root lewat proot.
tiny_enter() {
	R="${1:-$CONTAINER_DIR}"
	if [ ! -d "$R/usr" ] && [ ! -d "$R/bin" ]; then
		echo "tiny_enter: $R is not a Linux root filesystem"
		return 1
	fi
	( CONTAINER_DIR="$R"; @GENERIC_BOOT@ )
}

# Ekstrak rootfs .tar.xz / .tar.gz / .tar ke folder (default: $CONTAINER_DIR). Untuk .tar.zst pakai tombol Import.
tiny_extract() {
	A="$1"
	D="${2:-$CONTAINER_DIR}"
	if [ ! -f "$A" ]; then
		echo "usage: tiny_extract rootfs.tar.xz|.tar.gz|.tar [dir]"
		return 1
	fi
	case "$A" in
		*.tar.xz|*.txz) F=-J ;;
		*.tar.gz|*.tgz) F=-z ;;
		*.tar) F= ;;
		*) echo "tiny_extract: unsupported file (tar.zst: use the Import button)"; return 1 ;;
	esac
	mkdir -p "$D"
	proot --link2symlink tar x $F -f "$A" -C "$D" --delay-directory-restore --preserve-permissions
}

clear
echo "Tiny Computer terminal (Android shell, no system loaded)"
echo "Tools: proot, tar, xz, gzip, busybox <applet>"
echo "Own Linux system:"
echo "  tiny_extract rootfs.tar.xz   extract an arm64 rootfs into \$CONTAINER_DIR"
echo "  tiny_enter [dir]             enter it as root through proot"
echo "  tar -cJf out.tar.xz -C dir . pack it, then use the Import button on the Desktop page"
echo
