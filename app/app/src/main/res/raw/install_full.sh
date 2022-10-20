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
tar xf full-rootfs.tar.xz

# include resolv.conf
rm etc/resolv.conf
cat <<EOF > etc/resolv.conf
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

pwd
ls -al etc/resolv.conf
cat etc/resolv.conf
ls -al /full-distro/etc/resolv.conf
cat /full-distro/etc/resolv.conf

echo "bootstrap ready, run with run-bootstrap.sh"