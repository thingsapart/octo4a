#!/bin/sh

set -e
set -x

groupadd moonraker-admin
usermod -a -G moonraker-admin klipper
usermod -a -G moonraker-admin root

cd
chmod 700 /etc/dpkg/dpkg.cfg.d
apt-get install -q -y python3 python3-virtualenv virtualenv git unzip 2>&1 || true

rm -rf kiauh
curl -L -s https://github.com/th33xitus/kiauh/archive/refs/heads/master.zip -o kiauh_master.zip
unzip kiauh_master.zip
mv kiauh-master kiauh

# Backup virtualenv, will be replaced by a shim.
VENV=`which virtualenv`
mv ${VENV} ${VENV}-org
cp /root/virtualenv ${VENV}
chmod a+x ${VENV}

cp scripts/* kiauh
chmod a+x kiauh/*.sh