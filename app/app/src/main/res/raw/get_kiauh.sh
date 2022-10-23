#!/bin/sh

set -e
set -x

KIAUH_VERSION=5.0.0

cd

! {
groupadd moonraker-admin
usermod -a -G moonraker-admin klipper
usermod -a -G moonraker-admin root
usermod -a -G tty root
usermod -a -G dialout root

chmod 700 /etc/dpkg/dpkg.cfg.d
apt-get install -q -y curl python3 python3-virtualenv virtualenv git unzip 2>&1 || true
rm -rf kiauh
}

# Downloading a zip is no longer working since Kiauh v.5.0.0
# curl -L -s https://github.com/th33xitus/kiauh/archive/refs/tags/v${KIAUH_VERSION}.zip -o kiauh_master.zip
# unzip kiauh_master.zip
# mv kiauh-${KIAUH_VERSION} kiauh

git clone --branch v${KIAUH_VERSION} --single-branch https://github.com/th33xitus/kiauh.git

# Backup virtualenv, will be replaced by a shim.
VENV=`which virtualenv`
mv ${VENV} ${VENV}-org
cp /root/virtualenv ${VENV}
chmod a+x ${VENV}

cp scripts/* kiauh
chmod a+x kiauh/*.sh