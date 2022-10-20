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

echo "INSTALLING MOONRAKER"

source kiauh_preamble.sh

function run() {
  ### return early if python version check fails
  if [[ $(python3_check) == "false" ]]; then
  local error="Versioncheck failed! Python 3.7 or newer required!\n"
  error="${error} Please upgrade Python."
  print_error "${error}" && return
  fi
  
  ### return early if moonraker already exists
  local moonraker_services
  moonraker_services=$(moonraker_systemd)
  if [[ -n ${moonraker_services} ]]; then
  local error="At least one Moonraker service is already installed:"
  for s in ${moonraker_services}; do
  log_info "Found Moonraker service: ${s}"
  error="${error}\n ➔ ${s}"
  done
  print_error "${error}" && return
  fi
  
  ### return early if klipper is not installed
  local klipper_services
  klipper_services=$(klipper_systemd)
  if [[ -z ${klipper_services} ]]; then
    local error="Klipper not installed! Please install Klipper first!"
    log_error "Moonraker setup started without Klipper being installed. Aborting setup."
    print_error "${error}" && return
  fi
  
  local klipper_count user_input=() klipper_names=()
  klipper_count=$(echo "${klipper_services}" | wc -w )
  for service in ${klipper_services}; do
    klipper_names+=( "$(get_instance_name "${service}")" )
  done
  
  local moonraker_count
  if (( klipper_count == 1 )); then
    ok_msg "Klipper installation found!\n"
    moonraker_count=1
  elif (( klipper_count > 1 )); then
    log_error "Internal error. Only one klipper instance supported"
    exit 1
  fi
  
  user_input=()
  user_input+=("1")
  
  moonraker_setup "${user_input[@]}"
}

run

# Fix config mainsail nginx config:
# Change serving root directory to /root and listen on port 3000
# (Android blocks privileged ports).
sed -i -g "s/listen 80/listen 3000/g" /etc/nginx/sites-enabled/mainsail
sed -i -g "s/\/home\/root\//\/root\//g" /etc/nginx/sites-enabled/mainsail

echo ">> DONE INSTALLING MOONRAKER"
exit 0