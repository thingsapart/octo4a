#!/bin/bash

retries=3
wait_sec=5

set -e
set -x

retries=3
wait_sec=5

set -e
set -x
retries=3
wait_sec=5

set -e
function with_retries {
  for ((i=1; i<=retries; i++)); do
      echo "::> $@"
      if $@ 2>&1; then
        echo "RET: $?"
        break
      fi
      echo "NORET: $?"
      if [ "$i" == "$retries" ]; then
        exit 1
      fi
      echo ">> Failed - retrying in ${wait_sec}s."
      sleep ${wait_sec}
  done
  echo "DONE"
}

cd /root
with_retries "rm -rf klipper klippy-env"
with_retries "git clone --branch master --single-branch https://github.com/Klipper3d/klipper.git"

cd "/root/klipper/scripts"
sed "s/\(verify_ready\|install_packages\|create_virtualenv\|start_software\|install_script\)\s*$//g" install-debian.sh > install-deb-src.sh
cat "install-deb-src.sh"
source "install-deb-src.sh"

with_retries install_packages
with_retries create_virtualenv
with_retries install_script
with_retries start_software

mkdir -p /system_status
with_retries touch /system_status/klipper.installed