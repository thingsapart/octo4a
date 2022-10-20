#!/bin/sh

echo "BASE SETUP:"
set -x

echo "DPKGINFO"
#ls -al /var/lib/dpkg/info/
#touch /var/lib/dpkg/info/test
echo "DPKG-PRECONF"
# ls -al "/usr/sbin/dpkg-preconfigure"
# head -n 1000 /usr/sbin/dpkg-preconfigure

#mv /usr/share/debconf/frontend  /usr/share/debconf/frontend.org || true
#mv /usr/sbin/dpkg-preconfigure /usr/sbin/dpkg-preconfigure.org || true
#ln -s /usr/bin/nop.sh /usr/share/debconf/frontend
#ln -s /usr/bin/nop.sh /usr/bin/update-rc.d
#ln -s /usr/bin/nop.sh /usr/sbin/dpkg-preconfigure

apt-get update --allow-releaseinfo-change 2>&1
apt-get install -q -y --reinstall adduser 2>&1
apt-get install -q -y --reinstall perl 2>&1
apt-get install -q -y openssh-server 2>&1
cat /etc/resolv.conf
echo "PermitRootLogin yes" >> /etc/ssh/sshd_config
ssh-keygen -A
/etc/init.d/sshd start

for package in "dropbear curl bash sudo git unzip inetutils-traceroute"; do
  apt-get install -q -y "$package" 2>&1
done

ssh-keygen -A 2>&1

mkdir /home/klipper
useradd -U -m -d /home/klipper klipper 2>&1
echo 'klipper     ALL=(ALL) NOPASSWD:ALL' >> /etc/sudoers
