#!/usr/bin/env bash

HOME="/root"

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

source kiauh_preamble.sh

# Set up for directly calling klipper_setup()
user_input=()
user_input+=("python3")
user_input+=("1")

klipper_setup "${user_input[@]}"

# Octo4A uses a "shimmed" ioctlHook lib to intercept ioctls to the serial port, we need to preload it.
# So copy a preload script and rewrite KLIPPY_ENV to call this from systemd scripts instead and prefix
# the intended command.
cp ${SCRIPT_DIR}/ld_preload.sh ${KLIPPY_ENV}
KLIPPY_ENV="${KLIPPY_ENV}/ld_preload.sh ${KLIPPY_ENV}"

# Copied from Kiauh callsite of write_klipper_service(), here we call it again with modified KLIPPY_ENV
# to rewrite systemd service with proper ld-preload. We can't monkey-patch KLIPPY_ENV earlier.

python_version="python3"
user_input=()
klipper_initd_service=$(find_klipper_initd)
klipper_systemd_services=$(find_klipper_systemd)
user_input+=("${python_version}")
klipper_count="1"
user_input+=("${klipper_count}")

### if no custom names are used, add the respective amount of indices to the user_input array
for (( i=1; i <= klipper_count; i++ )); do
  user_input+=("${i}")
done

klipper_setup "${user_input[@]}"

printer_data="${HOME}/printer_data"
cfg_dir="${KLIPPER_CONFIG}"
cfg="${cfg_dir}/printer.cfg"
log="${KLIPPER_LOGS}/klippy.log"
printer="/tmp/printer"
uds="/tmp/klippy_uds"
service="${SYSTEMD}/klipper.service"
printer_data="${HOME}/printer_data"
env_file="${printer_data}/systemd/klipper.env"
### write single instance service
sudo rm -f ${service}
write_klipper_service "" "${cfg}" "${log}" "${printer}" "${uds}" "${service}" "${env_file}"

echo ">> DONE INSTALLING KLIPPER"

exit 0