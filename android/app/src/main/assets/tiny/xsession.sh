#!/bin/bash
# Memilih sesi desktop X11 yang tersedia di rootfs. Dijalankan Launcher.guiCommand dengan argumen "xinit/Xsession"
# (penanda supaya Shutdown bisa mengenali dan mematikan pohon prosesnya). Rootfs Tiny Computer lama membawa
# /etc/X11/xinit/Xsession; rootfs ekspor Tiny Container master tidak, jadi dicarikan sesi lain.
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
