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
# Ubuntu (/etc/os-release: ID=ubuntu atau ID_LIKE memuat ubuntu): jendela terminal tidak boleh muncul sendiri saat Open desktop.
# Penyebabnya bisa tiga: (1) entri autostart terminal (/etc/xdg/autostart), (2) XFCE memulihkan sesi tersimpan yang berisi
# terminal, (3) Xsession Debian/Ubuntu jatuh ke "failsafe" x-terminal-emulator bila tidak menemukan x-session-manager atau
# x-window-manager. Ketiganya ditutup di bawah. TINY_UBUNTU_TERMINAL=1 mematikan perlakuan ini (perilaku lama).
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

# Penjaga wallpaper (autostart, satu bus D-Bus dengan xfconfd). Rootfs Ubuntu tanpa paket artwork Xubuntu membawa
# setelan bawaan xfce4-desktop yang menunjuk /usr/share/xfce4/backdrops/xubuntu-wallpaper.png, padahal berkas itu tidak
# ada. xfdesktop lalu gagal memuat gambar ("Failed to load image file ... No such file or directory") dan menggambar
# warna latar bawaannya, yaitu hitam, ke window desktop. Itu bukan kerusakan X++: PutImage dan CopyArea wallpaper
# sampai ke window desktop dengan benar, isinya memang hitam. Penjaga ini memeriksa setiap properti last-image dan
# image-path di channel xfce4-desktop; bila berkasnya tidak ada, diganti gambar pertama yang ditemukan di rootfs.
# Bila rootfs tidak punya gambar sama sekali, dipakai gradien biru tua supaya tidak hitam polos.
# - TINY_KEEP_WALLPAPER=1: penjaga tidak dipasang (setelan lama).
if [ "${TINY_KEEP_WALLPAPER:-0}" != 1 ]; then
	wg="$HOME/.vnc/tiny-wallpaper-guard.sh"
	mkdir -p "${wg%/*}" "$HOME/.config/autostart"
	cat > "$wg" <<'WGUARD'
#!/bin/sh
command -v xfconf-query >/dev/null 2>&1 || exit 0
ch=xfce4-desktop
find_image() {
	for d in /usr/share/backgrounds/xfce /usr/share/xfce4/backdrops /usr/share/backgrounds /usr/share/images/desktop-base /usr/share/wallpapers; do
		[ -d "$d" ] || continue
		f=$(find "$d" -maxdepth 3 -type f \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' \) 2>/dev/null | sort | head -n 1)
		if [ -n "$f" ]; then printf '%s\n' "$f"; return 0; fi
	done
	return 1
}
fix_once() {
	changed=0
	props=$(xfconf-query -c "$ch" -l 2>/dev/null | grep -E '/(last-image|image-path)$')
	[ -n "$props" ] || return 1
	img=$(find_image)
	for p in $props; do
		v=$(xfconf-query -c "$ch" -p "$p" 2>/dev/null)
		[ -n "$v" ] || continue
		[ -e "$v" ] && continue
		pre=${p%/*}
		if [ -n "$img" ]; then
			xfconf-query -c "$ch" -p "$p" -s "$img" 2>/dev/null && changed=1 &&
				echo "[tiny] wallpaper: $v tidak ada, diganti $img ($p)"
			xfconf-query -c "$ch" -p "$pre/image-style" -n -t int -s 5 2>/dev/null ||
				xfconf-query -c "$ch" -p "$pre/image-style" -s 5 2>/dev/null
		else
			xfconf-query -c "$ch" -p "$pre/image-style" -n -t int -s 0 2>/dev/null ||
				xfconf-query -c "$ch" -p "$pre/image-style" -s 0 2>/dev/null
			xfconf-query -c "$ch" -p "$pre/color-style" -n -t int -s 2 2>/dev/null ||
				xfconf-query -c "$ch" -p "$pre/color-style" -s 2 2>/dev/null
			xfconf-query -c "$ch" -p "$pre/rgba1" -n -a -t double -s 0.10 -t double -s 0.17 -t double -s 0.30 -t double -s 1 2>/dev/null
			xfconf-query -c "$ch" -p "$pre/rgba2" -n -a -t double -s 0.02 -t double -s 0.04 -t double -s 0.10 -t double -s 1 2>/dev/null
			changed=1
			echo "[tiny] wallpaper: $v tidak ada dan rootfs tidak punya gambar, dipakai gradien warna ($pre)"
		fi
	done
	[ "$changed" = 1 ]
}
# xfdesktop membuat properti per-monitor sesudah jalan, jadi diperiksa beberapa kali.
for t in 2 3 5 8 12; do
	sleep "$t"
	pgrep -x xfdesktop >/dev/null 2>&1 || continue
	fix_once && break
done
exit 0
WGUARD
	printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=Tiny wallpaper guard' "Exec=sh $wg" \
		'OnlyShowIn=XFCE;' 'NoDisplay=true' 'X-GNOME-Autostart-enabled=true' > "$HOME/.config/autostart/tiny-wallpaper-guard.desktop"
fi

export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"
export XDG_SESSION_TYPE=x11
export DISPLAY="${DISPLAY:-:4}"

# tiny_is_ubuntu: sistem Linux di kontainer ini Ubuntu (atau turunannya)?
tiny_is_ubuntu() {
	[ -r /etc/os-release ] || return 1
	( . /etc/os-release 2>/dev/null; case " $ID $ID_LIKE " in *" ubuntu "*) exit 0 ;; esac; exit 1 )
}

tiny_noterm=0
if [ "${TINY_UBUNTU_TERMINAL:-0}" != 1 ] && tiny_is_ubuntu; then
	tiny_noterm=1
	echo "[tiny] Ubuntu terdeteksi: terminal tidak dijalankan otomatis saat Open desktop (TINY_UBUNTU_TERMINAL=1 untuk mengizinkan)"
	# (1) autostart: berkas pengguna dengan nama yang sama menimpa /etc/xdg/autostart; Hidden=true = tidak dijalankan.
	# Berkas buatan pengguna sendiri (tanpa penanda) tidak ditimpa.
	ad="$HOME/.config/autostart"
	mkdir -p "$ad"
	for t in xterm uxterm x-terminal-emulator gnome-terminal org.gnome.Terminal xfce4-terminal lxterminal qterminal \
		konsole org.kde.konsole mate-terminal terminator tilix alacritty kitty; do
		f="$ad/$t.desktop"
		if [ ! -e "$f" ] || grep -q 'tiny-no-terminal' "$f" 2>/dev/null; then
			printf '%s\n' '[Desktop Entry]' 'Type=Application' "Name=$t" 'Hidden=true' 'X-GNOME-Autostart-enabled=false' \
				'# tiny-no-terminal' > "$f"
		fi
	done
	# (2) sesi XFCE tersimpan yang berisi terminal dibuang supaya tidak dipulihkan; sesi tanpa terminal dibiarkan
	for sf in "$HOME"/.cache/sessions/xfce4-session-* "$HOME"/.cache/sessions/*; do
		[ -f "$sf" ] || continue
		if grep -qiE 'terminal|xterm|konsole|tilix|terminator|alacritty|kitty' "$sf" 2>/dev/null; then
			rm -f "$sf"
			echo "[tiny] sesi tersimpan berisi terminal dibuang: ${sf##*/}"
		fi
	done
fi

# (3) Ubuntu: Xsession hanya dipakai bila ada x-session-manager; tanpa itu determine-startup memilih x-terminal-emulator
if [ -f /etc/X11/xinit/Xsession ] && { [ "$tiny_noterm" != 1 ] || command -v x-session-manager >/dev/null 2>&1; }; then
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

if [ -f /etc/X11/Xsession ] && { [ "$tiny_noterm" != 1 ] || command -v x-session-manager >/dev/null 2>&1 || command -v x-window-manager >/dev/null 2>&1; }; then
	echo "[tiny] sesi desktop: /etc/X11/Xsession"
	bash /etc/X11/Xsession
	exit $?
fi

# Ubuntu tanpa desktop terpasang: cukup window manager saja, tanpa terminal failsafe
if [ "$tiny_noterm" = 1 ]; then
	for w in x-window-manager xfwm4 openbox metacity marco icewm fluxbox; do
		command -v "$w" >/dev/null 2>&1 || continue
		echo "[tiny] sesi minimal tanpa terminal: $w"
		if [ -z "$DBUS_SESSION_BUS_ADDRESS" ] && command -v dbus-launch >/dev/null 2>&1; then
			exec dbus-launch --exit-with-session "$w"
		fi
		exec "$w"
	done
fi

echo "[tiny] tidak ada sesi desktop (startxfce4, startlxqt, ... atau /etc/X11/Xsession) di rootfs ini" >&2
exit 1
