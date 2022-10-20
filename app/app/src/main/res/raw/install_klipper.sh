#!/usr/bin/env bash

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

source kiauh_preamble.sh

# Overwrite clone_klipper. Somehow git does not work when called from scripts in proot? Locking?
function clone_klipper__not_used() {
  local repo=${1} branch=${2}

  [[ -z ${repo} ]] && repo="${KLIPPER_REPO}"
  repo=$(echo "${repo}" | sed -r "s/^(http|https):\/\/github\.com\///i; s/\.git$//")
  repo="https://github.com/${repo}"

  [[ -z ${branch} ]] && branch="master"

  ### force remove existing klipper dir and clone into fresh klipper dir
  [[ -d ${KLIPPER_DIR} ]] && rm -rf "${KLIPPER_DIR}"

  status_msg "Downloading Klipper from ${repo} ..."

  cd "${HOME}" || exit 1
  if curl -L -s ${repo}/archive/refs/heads/${branch}.zip -o ${KLIPPER_DIR}; then
    status_msg "Success!"
  else
    print_error "Cloning Klipper from\n ${repo}\n failed!"
    exit 1
  fi
}

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
cfg_dir="${KLIPPER_CONFIG}"
cfg="${cfg_dir}/printer.cfg"
log="${KLIPPER_LOGS}/klippy.log"
printer="/tmp/printer"
uds="/tmp/klippy_uds"
service="${SYSTEMD}/klipper.service"
### write single instance service
sudo rm -f ${service}
write_klipper_service "" "${cfg}" "${log}" "${printer}" "${uds}" "${service}"

echo ">> DONE INSTALLING KLIPPER"

exit 0