#!/bin/bash
PR_URL=$1

echo -n "Downloading new PROOT..."

mkdir /proot
cd /proot

ls -al
curl -s -L $PR_URL -o proot.tar.gz
echo "done"

pwd
ls -al

tar -xf proot.tar.gz
chmod a+x root/bin/proot*
ls -al