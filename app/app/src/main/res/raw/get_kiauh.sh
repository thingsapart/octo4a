#!/bin/sh

set -e
set -x

cd /home/klipper

pwd
ls -al

rm -rf kiauh
curl -L -s https://github.com/th33xitus/kiauh/archive/refs/heads/master.zip -o kiauh_master.zip
unzip kiauh_master.zip
mv kiauh-master kiauh

cp /home/klipper/scripts/* kiauh
chmod a+x kiauh/*.sh