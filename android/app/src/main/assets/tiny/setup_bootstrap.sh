export DATA_DIR=@DATA_PATH@
export LD_LIBRARY_PATH=$DATA_DIR/lib
cd $DATA_DIR
ln -sf ../applib/libexec_busybox.so $DATA_DIR/bin/busybox
ln -sf ../applib/libexec_busybox.so $DATA_DIR/bin/sh
ln -sf ../applib/libexec_busybox.so $DATA_DIR/bin/cat
ln -sf ../applib/libexec_busybox.so $DATA_DIR/bin/xz
ln -sf ../applib/libexec_busybox.so $DATA_DIR/bin/gzip
ln -sf ../applib/libexec_proot.so $DATA_DIR/bin/proot
ln -sf ../applib/libexec_tar.so $DATA_DIR/bin/tar
ln -sf ../applib/libexec_virgl_test_server.so $DATA_DIR/bin/virgl_test_server
ln -sf ../applib/libexec_getifaddrs_bridge_server.so $DATA_DIR/bin/getifaddrs_bridge_server
ln -sf ../applib/libexec_pulseaudio.so $DATA_DIR/bin/pulseaudio
ln -sf ../applib/libacl.so $DATA_DIR/lib/libacl.so
ln -sf ../applib/libandroid-selinux.so $DATA_DIR/lib/libandroid-selinux.so
ln -sf ../applib/libattr.so $DATA_DIR/lib/libattr.so
ln -sf ../applib/libbusybox.so $DATA_DIR/lib/libbusybox.so.1.37.0
ln -sf ../applib/libiconv.so $DATA_DIR/lib/libiconv.so
ln -sf ../applib/libpcre2-8.so $DATA_DIR/lib/libpcre2-8.so
ln -sf ../applib/libtalloc.so $DATA_DIR/lib/libtalloc.so.2
ln -sf ../applib/libvirglrenderer.so $DATA_DIR/lib/libvirglrenderer.so
ln -sf ../applib/libepoxy.so $DATA_DIR/lib/libepoxy.so
ln -sf ../applib/libproot-loader32.so $DATA_DIR/lib/loader32
ln -sf ../applib/libproot-loader.so $DATA_DIR/lib/loader

$DATA_DIR/bin/busybox unzip -o assets.zip
chmod -R +x bin/*
chmod -R +x libexec/proot/*
chmod 1777 tmp
$DATA_DIR/bin/tar zxf patch.tar.gz
$DATA_DIR/bin/busybox rm -rf assets.zip patch.tar.gz
