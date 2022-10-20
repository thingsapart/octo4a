#!/usr/bin/env bash

source kiauh_preamble.sh

# Set up for directly calling klipper_setup()
user_input=()
user_input+=("python3")
user_input+=("1")

klipper_setup "${user_input[@]}"

# Octo4A uses a "shimmed" ioctlHook lib to intercept ioctls to the serial port, we need to preload it.
# So copy a launch script and rewrite KLIPPY_ENV to call this script instead and prefix the original command.
cp ~/ld_preload.sh ${KLIPPY_ENV}
KLIPPY_ENV="${KLIPPY_ENV}ld_preload.sh ${KLIPPY_ENV}"

# Copied from Kiauh callsite of write_klipper_service(), here we call it again with modified KLIPPY_ENV
# to rewrite systemd service with proper ld-preload. We can't monkey-patch KLIPPY_ENV earlier.
cfg_dir="${KLIPPER_CONFIG}"
cfg="${cfg_dir}/printer.cfg"
log="${KLIPPER_LOGS}/klippy.log"
printer="/tmp/printer"
uds="/tmp/klippy_uds"
service="${SYSTEMD}/klipper.service"
### write single instance service
sudo rm -f ${service}
write_klipper_service "" "${cfg}" "${log}" "${printer}" "${uds}" "${service}"
