#!/bin/sh

rm -rf kiauh
/usr/bin/git clone https://github.com/th33xitus/kiauh.git

sudo chown -R klipper *

cp scripts/* kiauh
chmod a+x kiauh/*.sh