export DATA_DIR=@DATA_PATH@
export PATH=$DATA_DIR/bin:$PATH
export LD_LIBRARY_PATH=$DATA_DIR/lib
export CONTAINER_DIR=$DATA_DIR/containers/0
cd $DATA_DIR
BB="$DATA_DIR/bin/busybox"
# Titik mount yang dipakai perintah boot proot; rootfs minimal (Ubuntu base, dll.) tidak selalu memilikinya
cd "$CONTAINER_DIR"
mkdir -p proc sys dev tmp data system apex storage media/sd mnt root etc
chmod 1777 tmp
chmod 700 root
cd $DATA_DIR
# Buang akun/grup aid_ lama (rootfs hasil ekspor), lalu tambahkan akun/grup Android seperti proot-distro
for f in passwd shadow group gshadow; do
	if [ -f "$CONTAINER_DIR/etc/$f" ]; then
		chmod u+rw "$CONTAINER_DIR/etc/$f"
		$BB sed -i '/^aid_/d' "$CONTAINER_DIR/etc/$f"
	fi
done
if [ -f "$CONTAINER_DIR/etc/passwd" ]; then
	echo "aid_$(id -un):x:$(id -u):$(id -g):Termux:/:/sbin/nologin" >> "$CONTAINER_DIR/etc/passwd"
fi
if [ -f "$CONTAINER_DIR/etc/shadow" ]; then
	echo "aid_$(id -un):*:18446:0:99999:7:::" >> "$CONTAINER_DIR/etc/shadow"
fi
if [ -f "$CONTAINER_DIR/etc/group" ]; then
	id -Gn | tr ' ' '\n' > tmp1
	id -G | tr ' ' '\n' > tmp2
	$BB paste tmp1 tmp2 > tmp3
	cat tmp3 | while read -r group_name group_id; do
		echo "aid_${group_name}:x:${group_id}:root,aid_$(id -un)" >> "$CONTAINER_DIR/etc/group"
		if [ -f "$CONTAINER_DIR/etc/gshadow" ]; then
			echo "aid_${group_name}:*::root,aid_$(id -un)" >> "$CONTAINER_DIR/etc/gshadow"
		fi
	done
	$BB rm -f tmp1 tmp2 tmp3
fi
# DNS: resolv.conf bawaan rootfs biasanya menunjuk ke systemd-resolved yang tidak ada di proot
$BB rm -f "$CONTAINER_DIR/etc/resolv.conf"
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > "$CONTAINER_DIR/etc/resolv.conf"
[ -f "$CONTAINER_DIR/etc/hosts" ] || echo "127.0.0.1 localhost" > "$CONTAINER_DIR/etc/hosts"
# apt di proot tidak bisa berpindah ke user _apt: jalankan sandbox sebagai root
if [ -d "$CONTAINER_DIR/etc/apt/apt.conf.d" ]; then
	echo 'APT::Sandbox::User "root";' > "$CONTAINER_DIR/etc/apt/apt.conf.d/99tiny-proot"
fi
