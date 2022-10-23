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

function clone_klipper {
  rm -rf klipper klippy-env
  git clone --branch master --single-branch https://github.com/Klipper3d/klipper.git
}

with_retries "chmod a+x /root/scripts/ld_preload.sh"

# /usr/lib/aarch64-linux-gnu/libc.so seem to be an LD script not accepted by ioctlHook.so so we symlink it?!
with_retries "mv /usr/lib/aarch64-linux-gnu/libc.so /usr/lib/aarch64-linux-gnu/libc.so.org"
with_retries "ln -s /lib/aarch64-linux-gnu/libc.so.6  /usr/lib/aarch64-linux-gnu/libc.so"

cd /root
with_retries clone_klipper

cd "/root/klipper/scripts"
sed "s/\(verify_ready\|install_packages\|create_virtualenv\|start_software\|install_script\)\s*$//g" install-debian.sh > install-deb-src.sh
sed -i "s/python-dev/python3-dev/g" "install-deb-src.sh"
sed -i "s/python2/python3/g" "install-deb-src.sh"
sed -i "s/ExecStart=/ExecStart=\/root\/scripts\/ld_preload.sh /g" "install-deb-src.sh"
sed -i "s/printer.cfg/printer_data/config/printer.cfg/g" "install-deb-src.sh"
cat "install-deb-src.sh"
source "install-deb-src.sh"

with_retries install_packages
with_retries create_virtualenv
with_retries install_script
# with_retries start_software

mkdir -p /system_status
with_retries touch /system_status/klipper.installed

echo ">> DONE INSTALLING KLIPPER"

# For a to-me unknown reason the bash script sometimes hangs.
echo ""
echo ""
echo ""