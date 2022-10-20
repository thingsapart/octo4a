#!/system/bin/sh

apk update
apk add curl

# unpack  bootstrap
pwd
ls -al
mkdir /full-distro
cd /full-distro
ls -al

curl -s -L "$1" -o full-rootfs.tar.xz
ls -al
export LANG="en_US.UTF-8"
LANG="en_US.UTF-8"
tar xvf full-rootfs.tar.xz

# include resolv.conf
echo "nameserver 8.8.8.8 \n \
nameserver 8.8.4.4" > etc/resolv.conf

echo "bootstrap ready, run with run-bootstrap.sh"