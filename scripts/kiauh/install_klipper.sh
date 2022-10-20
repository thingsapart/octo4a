#!/usr/bin/env bash

source kiauh_preamble.sh

user_input=()
user_input+=("python3")
user_input+=("1")

klipper_setup "${user_input[@]}"

KLIPPY_ENV="LD_PRELOAD=/home/octoprint/ioctlHook.so ${KLIPPY_ENV}"
cfg_dir="${KLIPPER_CONFIG}"
cfg="${cfg_dir}/printer.cfg"
log="${KLIPPER_LOGS}/klippy.log"
printer="/tmp/printer"
uds="/tmp/klippy_uds"
service="${SYSTEMD}/klipper.service"
### write single instance service
sudo rm -f ${service}
write_klipper_service "" "${cfg}" "${log}" "${printer}" "${uds}" "${service}"
