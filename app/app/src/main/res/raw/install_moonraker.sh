#!/bin/bash

#TAG=v0.7.1

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

function clone_moonraker() {
  rm -rf moonraker moonraker-env
  if [ "$TAG" != "" ]; then
    git clone --branch $TAG --single-branch https://github.com/Arksine/moonraker.git
  else
    git clone --depth 1 https://github.com/Arksine/moonraker.git
  fi
}

cd /root
with_retries clone_moonraker

for package in libjpeg-dev zlib1g-dev; do
  with_retries "apt-get install -q -y $package"
done

cd "/root/moonraker/scripts"
sed "s/\(init_data_path\|check_polkit_rules\|check_klipper\|cleanup_legacy\|verify_ready\|install_packages\|create_virtualenv\|start_software\|install_script\)\s*$/eval ''/g" "install-moonraker.sh" > "install-moonraker-src.sh"
cat "install-moonraker-src.sh"

source "install-moonraker-src.sh"

with_retries cleanup_legacy
with_retries install_packages
with_retries create_virtualenv
with_retries init_data_path
with_retries install_script
# with_retries check_polkit_rules

if [ $DISABLE_SYSTEMCTL = "n" ]; then
    start_software
fi

mkdir -p /system_status
with_retries touch /system_status/moonraker.installed

echo ">> DONE INSTALLING MOONRAKER"
echo ""
echo ""
echo ""