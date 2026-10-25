#!/bin/bash
# Memilih sesi desktop X11 yang tersedia di rootfs. Dijalankan Launcher.guiCommand dengan argumen "xinit/Xsession"
# (penanda supaya Shutdown bisa mengenali dan mematikan pohon prosesnya). Rootfs Tiny Computer lama membawa
# /etc/X11/xinit/Xsession; rootfs ekspor Tiny Container master tidak, jadi dicarikan sesi lain.
# xfwm4 dengan compositor menyala membuat layar hitam di Xwayland rootful (kursor masih bereaksi). Sesi Shortcut
# sudah menyalakan xfwm4 dengan --compositor=off; sesi desktop dipaksa sama lewat berkas xfconf sebelum xfce4-session
# jalan. Isi TINY_KEEP_COMPOSITOR=1 (Pengaturan shortcut > Env atau shell kontainer) untuk membiarkan setelan XFCE.
if [ "${TINY_KEEP_COMPOSITOR:-0}" != 1 ]; then
	xf="$HOME/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml"
	mkdir -p "${xf%/*}"
	if [ ! -s "$xf" ]; then
		printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>' '' '<channel name="xfwm4" version="1.0">' \
			'  <property name="general" type="empty">' '    <property name="use_compositing" type="bool" value="false"/>' \
			'  </property>' '</channel>' > "$xf"
	elif grep -q 'name="use_compositing"' "$xf"; then
		sed -i 's|\(name="use_compositing"[^>]*value="\)[^"]*"|\1false"|' "$xf"
	elif grep -q '<property name="general"' "$xf"; then
		sed -i '0,/<property name="general"[^>]*>/s||&\n    <property name="use_compositing" type="bool" value="false"/>|' "$xf"
	fi
fi

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
