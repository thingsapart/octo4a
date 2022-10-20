#!/bin/sh

set -e

git clone "https://github.com/th33xitus/kiauh.git"
cd kiauh
curl -s https://github.com/thingsapart/octo4a/raw/master/scripts/kiauh/kiauh_preamble.sh -o kiauh_preamble.sh
curl -s https://github.com/thingsapart/octo4a/raw/master/scripts/kiauh/install_klipper.sh -o install_klipper.sh
curl -s https://github.com/thingsapart/octo4a/raw/master/scripts/kiauh/install_moonraker.sh -o install_moonraker.sh
curl -s https://github.com/thingsapart/octo4a/raw/master/scripts/kiauh/install_mainsail.sh -o install_mainsail.sh
