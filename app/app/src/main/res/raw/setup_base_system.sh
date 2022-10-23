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
  for ((i=0; i<retries; i++)); do
      echo "::> $@"
      if $@ 2>&1; then
        echo "RET: $?"
        break
      fi
      echo "NORET: $?"
      echo ">> Failed - retrying in ${wait_sec}s."
      sleep ${wait_sec}
  done
  echo "DONE"
}


with_retries "apt-get update --allow-releaseinfo-change"
for package in dropbear curl bash sudo python3 python3-virtualenv virtualenv git unzip; do
  with_retries "apt-get install -q -y ${package}"
done

# Setup docker-systemctl-replacement systemctl simulation.
with_retries "mv /bin/systemctl /bin/systemctl.org"
with_retries "mv /bin/systemctl.new /bin/systemctl"
with_retries "chmod a+x /bin/systemctl"

# Setup ssh.
with_retries "ssh-keygen -A"

# Add klipper user.
#with_retries "sh add-user.sh klipper"
with_retries "/usr/sbin/adduser klipper --gecos 'Klipper User,RoomNumber,WorkPhone,HomePhone' --disabled-password"
with_retries "echo 'klipper     ALL=(ALL) NOPASSWD:ALL' >> /etc/sudoers"

mkdir -p /system_status
with_retries touch /system_status/base_system.installed