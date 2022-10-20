#!/system/bin/sh

export LANG='en_US.UTF-8'
export LC_CTYPE="en_US.UTF-8"
export LC_NUMERIC="en_US.UTF-8"
export LC_TIME="en_US.UTF-8"
export LC_COLLATE=C
export LC_MONETARY="en_US.UTF-8"
export LC_MESSAGES="en_US.UTF-8"
export LC_PAPER="en_US.UTF-8"
export LC_NAME="en_US.UTF-8"
export LC_ADDRESS="en_US.UTF-8"
export LC_TELEPHONE="en_US.UTF-8"
export LC_MEASUREMENT="en_US.UTF-8"
export LC_IDENTIFICATION="en_US.UTF-8"

locale -a
env

chmod -R 777 ../
chmod -R +rx ../

# unpack  bootstrap
mkdir bootstrap
cd bootstrap
cat ../rootfs.tar.xz | ../root/bin/minitar
export LANG='en_US.UTF-8'
LANG='en_US.UTF-8' cat ../rootfs.tar.xz | ../root/bin/minitar
export LANG='C.UTF-8'
LANG='C.UTF-8' cat ../rootfs.tar.xz | ../root/bin/minitar
export LANG='UTF-8'
LANG='UTF-8' cat ../rootfs.tar.xz | ../root/bin/minitar
cd ..

# include resolv.conf
echo "nameserver 8.8.8.8 \n \
nameserver 8.8.4.4" > bootstrap/etc/resolv.conf

echo "bootstrap ready, run with run-bootstrap.sh"