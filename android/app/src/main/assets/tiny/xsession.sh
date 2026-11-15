#!/bin/bash
# Memilih sesi desktop X11 yang tersedia di rootfs. Dijalankan Launcher.guiCommand dengan argumen "xinit/Xsession"
# (penanda supaya Shutdown bisa mengenali dan mematikan pohon prosesnya). Rootfs Tiny Computer lama membawa
# /etc/X11/xinit/Xsession; rootfs ekspor Tiny Container master tidak, jadi dicarikan sesi lain.
# xfwm4 dengan compositor menyala membuat layar hitam di Xwayland rootful (kursor masih bereaksi). Penyebabnya bukan
# compositing itu sendiri (XRender di CPU lewat wl_shm; xfwm4 tidak punya backend Vulkan), melainkan jalur sinkronisasi
# vblank xfwm4 (vblank_mode=auto memilih GLX lewat Zink atau xpresent) yang tidak pernah selesai di Xwayland tanpa DRM:
# xfwm4 menunggu notifikasi vblank yang tidak datang, lalu tidak pernah menggambar ke layar.
# Karena itu sesi desktop selalu memaksa vblank_mode=off (sebelum xfce4-session jalan, dan dijaga selama sesi supaya
# dialog Window Manager Tweaks tidak bisa menyalakannya lagi). Efek blur/transparansi tetap bisa dipakai.
# - bawaan: use_compositing dimatikan saat sesi mulai; bisa dinyalakan lewat Window Manager Tweaks selama sesi.
# - TINY_XFCE_COMPOSITOR=1 (Pengaturan > "Allow XFCE compositor"): use_compositing dibiarkan sesuai setelan XFCE.
# - TINY_KEEP_COMPOSITOR=1: sama, use_compositing tidak disentuh (setelan lama).
# - TINY_KEEP_VBLANK=1: vblank_mode tidak disentuh dan penjaga tidak dipasang.
xfwm_xml="$HOME/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml"

# tiny_xfwm_prop NAMA TIPE NILAI: set satu properti /general di xfwm4.xml tanpa D-Bus. Menangani berkas kosong atau tanpa
# channel xfwm4, <property name="general" .../> yang self-closing (dulu sisipan jatuh di luar general dan diabaikan
# xfconf, jadi compositor tetap menyala), properti yang sudah ada, dan general tanpa properti tersebut.
tiny_xfwm_prop() {
	n=$1 t=$2 v=$3
	mkdir -p "${xfwm_xml%/*}"
	if [ ! -s "$xfwm_xml" ] || ! grep -q '<channel name="xfwm4"' "$xfwm_xml"; then
		printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>' '' '<channel name="xfwm4" version="1.0">' \
			'  <property name="general" type="empty">' '  </property>' '</channel>' > "$xfwm_xml"
	fi
	sed -i 's|<property name="general" type="empty"/>|<property name="general" type="empty">\n  </property>|' "$xfwm_xml"
	if grep -q "name=\"$n\"" "$xfwm_xml"; then
		sed -i 's|\(name="'"$n"'"[^>]*value="\)[^"]*"|\1'"$v"'"|' "$xfwm_xml"
	elif grep -q '<property name="general"' "$xfwm_xml"; then
		sed -i '0,/<property name="general"[^>]*>/s||&\n    <property name="'"$n"'" type="'"$t"'" value="'"$v"'"/>|' "$xfwm_xml"
	else
		sed -i 's|</channel>|  <property name="general" type="empty">\n    <property name="'"$n"'" type="'"$t"'" value="'"$v"'"/>\n  </property>\n</channel>|' "$xfwm_xml"
	fi
}

comp_msg='off saat sesi mulai'
if [ "${TINY_KEEP_COMPOSITOR:-0}" = 1 ] || [ "${TINY_XFCE_COMPOSITOR:-0}" = 1 ]; then
	comp_msg='sesuai setelan XFCE'
else
	tiny_xfwm_prop use_compositing bool false
fi

vb_msg='tidak disentuh'
if [ "${TINY_KEEP_VBLANK:-0}" != 1 ]; then
	vb_msg=off
	tiny_xfwm_prop vblank_mode string off
	# Penjaga di dalam sesi XFCE (autostart, jadi satu bus D-Bus dengan xfconfd): bila vblank_mode berubah dari off,
	# dikembalikan lagi. xfwm4 membaca perubahan itu langsung, jadi layar tidak sempat macet.
	g="$HOME/.vnc/tiny-xfwm-guard.sh"
	mkdir -p "${g%/*}" "$HOME/.config/autostart"
	cat > "$g" <<'GUARD'
#!/bin/sh
command -v xfconf-query >/dev/null 2>&1 || exit 0
fix() {
	[ "$(xfconf-query -c xfwm4 -p /general/vblank_mode 2>/dev/null)" = off ] && return 0
	xfconf-query -c xfwm4 -p /general/vblank_mode -s off 2>/dev/null ||
		xfconf-query -c xfwm4 -p /general/vblank_mode -n -t string -s off 2>/dev/null
}
fix
xfconf-query -c xfwm4 -m 2>/dev/null | while read -r ev rest; do
	case "$ev $rest" in *vblank_mode*) fix ;; esac
done
GUARD
	printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=Tiny xfwm4 vblank guard' "Exec=sh $g" \
		'OnlyShowIn=XFCE;' 'NoDisplay=true' 'X-GNOME-Autostart-enabled=true' > "$HOME/.config/autostart/tiny-xfwm-guard.desktop"
fi
echo "[tiny] xfwm4: compositor $comp_msg, vblank $vb_msg"

export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"
export XDG_SESSION_TYPE=x11
export DISPLAY="${DISPLAY:-:4}"

if [ -f /etc/X11/xinit/Xsession ]; then
	exec bash /etc/X11/xinit/Xsession
fi

for s in startxfce4 startlxqt startplasma-x11 mate-session startlxde cinnamon-session gnome-session xfce4-session openbox-session; do
	if command -v "$s" >/dev/null 2>&1; then
		echo "[tiny] sesi desktop: $s"
		if [ "$s" != startxfce4 ] && [ -z "$DBUS_SESSION_BUS_ADDRESS" ] && command -v dbus-launch >/dev/null 2>&1; then
			dbus-launch --exit-with-session "$s"
		else
			"$s"
		fi
		exit $?
	fi
done

if [ -f /etc/X11/Xsession ]; then
	echo "[tiny] sesi desktop: /etc/X11/Xsession"
	bash /etc/X11/Xsession
	exit $?
fi

echo "[tiny] tidak ada sesi desktop (startxfce4, startlxqt, ... atau /etc/X11/Xsession) di rootfs ini" >&2
exit 1
