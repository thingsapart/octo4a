#!/usr/bin/env bash

#=======================================================================#
# Copyright (C) 2020 - 2022 Dominik Willner <th33xitus@gmail.com>       #
#                                                                       #
# This file is part of KIAUH - Klipper Installation And Update Helper   #
# https://github.com/th33xitus/kiauh                                    #
#                                                                       #
# This file may be distributed under the terms of the GNU GPLv3 license #
#=======================================================================#

# Modified to be able to be called without the Kiauh menu system.

echo "INSTALLING MAINSAIL"

set -e

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

cwd=`pwd`
cd /root/kiauh/
cp /root/scripts/kiauh_preamble.sh .
source kiauh_preamble.sh

function print_confirm {
  echo $@
}

function fetch_webui_ports {
  eval ''
}

function run {
    ### exit early if moonraker not found
    if [[ -z $(moonraker_systemd) ]]; then
      local error="Moonraker not installed! Please install Moonraker first!"
      print_error "${error}" && return
    fi

    ### checking dependencies
    local dep=(wget nginx)
    dependency_check "${dep[@]}"
    ### detect conflicting Haproxy and Apache2 installations
    detect_conflicting_packages

    status_msg "Initializing Mainsail installation ..."
    ### first, we create a backup of the full klipper_config dir - safety first!
    backup_klipper_config_dir

    ### check for other enabled web interfaces
    SET_LISTEN_PORT=3000
    detect_enabled_sites
    SET_LISTEN_PORT=3000

    ### check if another site already listens to port 80
    mainsail_port_check
    SET_LISTEN_PORT=3000

    ### download mainsail
    download_mainsail

    ### ask user to install the recommended webinterface macros
    download_mainsail_macros

    ### create /etc/nginx/conf.d/upstreams.conf
    set_upstream_nginx_cfg
    ### create /etc/nginx/sites-available/<interface config>
    set_nginx_cfg "mainsail"
    ### nginx on ubuntu 21 and above needs special permissions to access the files
    set_nginx_permissions

    ### symlink nginx log
    symlink_webui_nginx_log "mainsail"

    ### add mainsail to the update manager in moonraker.conf
    patch_mainsail_update_manager

    #fetch_webui_ports #WIP

    ### confirm message
    echo "Mainsail has been set up!"
}

run

# Fix config mainsail nginx config:
# Change serving root directory to /root and listen on port 3000
# (Android blocks privileged ports).
sed -i "s/listen 80/listen 3000/g" /etc/nginx/sites-enabled/mainsail
sed -i "s/\/home\/root\//\/root\//g" /etc/nginx/sites-enabled/mainsail

mkdir -p /system_status
with_retries touch /system_status/mainsail.installed

echo ">> DONE INSTALLING MAINSAIL"
exit 0
