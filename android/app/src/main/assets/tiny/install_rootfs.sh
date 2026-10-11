export DATA_DIR=@DATA_PATH@
export PATH=$DATA_DIR/bin:$PATH
export LD_LIBRARY_PATH=$DATA_DIR/lib
export CONTAINER_DIR=$DATA_DIR/containers/0
export EXTRA_OPT=""
cd $DATA_DIR
export PATH=$DATA_DIR/bin:$PATH
export PROOT_TMP_DIR=$DATA_DIR/proot_tmp
export PROOT_LOADER=$DATA_DIR/applib/libproot-loader.so
export PROOT_LOADER_32=$DATA_DIR/applib/libproot-loader32.so
#export PROOT_L2S_DIR=$CONTAINER_DIR/.l2s
# Ekstraksi rootfs (xa* -> containers/0) dijalankan Rootfs.extractRootfs() dari Kotlin supaya persennya terukur.
#Script from proot-distro
chmod u+rw "$CONTAINER_DIR/etc/passwd" "$CONTAINER_DIR/etc/shadow" "$CONTAINER_DIR/etc/group" "$CONTAINER_DIR/etc/gshadow"
echo "aid_$(id -un):x:$(id -u):$(id -g):Termux:/:/sbin/nologin" >> "$CONTAINER_DIR/etc/passwd"
echo "aid_$(id -un):*:18446:0:99999:7:::" >> "$CONTAINER_DIR/etc/shadow"
id -Gn | tr ' ' '\n' > tmp1
id -G | tr ' ' '\n' > tmp2
$DATA_DIR/bin/busybox paste tmp1 tmp2 > tmp3
local group_name group_id
cat tmp3 | while read -r group_name group_id; do
	echo "aid_${group_name}:x:${group_id}:root,aid_$(id -un)" >> "$CONTAINER_DIR/etc/group"
	if [ -f "$CONTAINER_DIR/etc/gshadow" ]; then
		echo "aid_${group_name}:*::root,aid_$(id -un)" >> "$CONTAINER_DIR/etc/gshadow"
	fi
done
$DATA_DIR/bin/busybox rm -rf xa* tmp1 tmp2 tmp3
@LOCALE_LINE@
