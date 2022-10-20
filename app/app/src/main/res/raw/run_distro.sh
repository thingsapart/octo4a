#!/system/bin/sh
# Minimal proot run script

if [ -z "$1" ] || [ -z "$2" ]; then
	echo "Usage: <user> <command>"
	exit 1
fi

BASE_DIR="$PWD"

export PROOT_TMP_DIR="$BASE_DIR/tmp"
export PROOT_L2S_DIR="$BASE_DIR/bootstrap/.proot.meta"

mkdir -p "$PROOT_TMP_DIR"
mkdir -p "$PROOT_L2S_DIR"

if [ "$1" = "root" ]; then
	PATH='/sbin:/usr/sbin:/bin:/usr/bin'
	USER='root'
	HOME='/root'
	OP="-0"
else
	OP=""
	USER="$1"
	PATH='/sbin:/usr/sbin:/bin:/usr/bin'
	HOME="/home/$USER"
fi

unset TMPDIR
unset LD_LIBRARY_PATH
export PATH
export USER
export HOME
shift
./root/bin/proot -r bootstrap $OP -b /dev -b /proc -b /sys -b /system -b /vendor -b /storage -b ${PWD}/fake_proc_stat:/proc/stat $EXTRA_BIND --link2symlink -p -L -w $HOME "$@"


exit 0

echo "DISTRO: $@"

if [ -z "$1" ] || [ -z "$2" ]; then
	echo "Usage: <user> <command>"
	exit 1
fi

BASE_DIR="$PWD"
echo "BASEDIR: $BASEDIR"
ls -al $BASE_DIR
ls -al $BASE_DIR/bootstrap
file /data/data/com.klipper4a/files/bootstrap/bootstrap/usr/lib/ld-linux-aarch64.so.1

export PROOT_TMP_DIR="$BASE_DIR/tmp"
export PROOT_L2S_DIR="$BASE_DIR/bootstrap/.proot.meta"

mkdir -p "$PROOT_TMP_DIR"
mkdir -p "$PROOT_L2S_DIR"

if [ "$1" = "root" ]; then
	PATH='/sbin:/usr/sbin:/bin:/usr/bin'
	USER='root'
	HOME='/root'
	OP="-0"
else
	OP=""
	USER="$1"
	PATH='/sbin:/usr/sbin:/bin:/usr/bin'
	HOME="/home/$USER"
fi


unset TMPDIR
unset LD_LIBRARY_PATH
export PATH
export USER
export HOME
shift

#set

# echo ./root/bin/proot -r bootstrap/full-distro -0 -b /dev -b /proc -b /sys -b /system -b /vendor -b /storage -b ${PWD}/fake_proc_stat:/proc/stat $EXTRA_BIND --link2symlink -p -L -w $HOME /usr/bin/sudo -u $USER -s -- "$1"
#./root/bin/proot -r bootstrap/full-distro -0 -b /dev -b /proc -b /sys:/android-sys -b /system:/android-system -b /vendor -b /storage -b ${PWD}/fake_proc_stat:/proc/stat $EXTRA_BIND --link2symlink -p -L -w $HOME /usr/bin/sudo -u $USER -i -- $1
#./root/bin/proot -v 10 -r bootstrap/full-distro -0 -b /dev -b /proc -b /sys -b /system -b /vendor -b /storage -b ${PWD}/fake_proc_stat:/proc/stat $EXTRA_BIND --link2symlink -p -L -w $HOME /usr/bin/sudo -u $USER -s -- "/bin/sh" "-c" "$1"
# ./root/bin/proot -r bootstrap/full-distro OP -b /dev -b /proc -b /sys -b /system -b /vendor -b /storage -b ${PWD}/fake_proc_stat:/proc/stat $EXTRA_BIND --link2symlink -p 80:3000 -L -w /bin/bash -c $1

# PREBOOTSTRAP
# echo ./root/bin/proot -r $PWD/bootstrap $OP -b /dev -b /proc -b /sys -b /system -b /vendor -b /storage -b ${PWD}/fake_proc_stat:/proc/stat $EXTRA_BIND --link2symlink -p -L -w $HOME $1

./root/bin/proot -r $PWD/bootstrap $OP -b /dev -b /proc -b /sys -b /system -b /vendor -b /storage -b ${PWD}/fake_proc_stat:/proc/stat --link2symlink -p -L -w $HOME file /usr/bin/dash
