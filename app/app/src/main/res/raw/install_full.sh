#!/bin/sh

set -x

setup_resolv_conf()
{
  # write new resolv.conf
  rm etc/resolv.conf
  cat <<EOF > etc/resolv.conf
nameserver 8.8.8.8
nameserver 8.8.4.4
EOF

  pwd
  ls -al etc/resolv.conf
  cat etc/resolv.conf
  ls -al /full-distro/etc/resolv.conf
  cat /full-distro/etc/resolv.conf
}

install_in_pre_bootstrap()
{
  apk update
  apk add curl

  # unpack distro
  pwd
  ls -al
  mkdir /full-distro
  cd /full-distro
  ls -al

  curl -s -L "$1" -o full-rootfs.tar.xz
  ls -al
  export LANG="en_US.UTF-8"
  tar xf full-rootfs.tar.xz

  setup_resolv_conf

  cp usr/bin/sh usr/bin/proot_sh
  cp usr/bin/bash usr/bin/proot_bash
  cp bin/sh bin/proot_sh
  cp bin/bash bin/proot_bash
}

install_minitar()
{
  export LANG='en_US.UTF-8'
  export LC_CTYPE="en_US.UTF-8"
  export LC_NUMERIC="en_US.UTF-8"
  export LC_TIME="en_US.UTF-8"
  export LC_COLLATE=C
  export LC_MONETARY="en_US.UTF-8"
  export LC_MESSAGES="en_US.UTF-8"
  export LC_PAPER="en_US.UTF-8"
  export LC_NAME="en_US.UTF-8"
  export LC_ADDRESS="en_US.UTF-8"
  export LC_TELEPHONE="en_US.UTF-8"
  export LC_MEASUREMENT="en_US.UTF-8"
  export LC_IDENTIFICATION="en_US.UTF-8"

  locale -a
  env

  chmod -R 777 ../
  chmod -R +rx ../

  # unpack  bootstrap
  mkdir bootstrap
  cd bootstrap
  LANG='en_US.UTF-8' cat ../rootfs.tar.xz | ../root/bin/minitar
  LANG='C.UTF-8' cat ../rootfs.tar.xz | ../root/bin/minitar
  LANG='UTF-8' cat ../rootfs.tar.xz | ../root/bin/minitar

  setup_resolv_conf

  cd ..
}

setup_rootfs()
{
  cd bootstrap
  setup_resolv_conf
}

# install_in_pre_bootstrap "$1"
install_minitar "$1"

echo "INSTALL FULL:"
#setup_rootfs

echo "installation ready, run with run-bootstrap.sh"