#!/system/bin/sh

echo "NEW INSTALL BOOTSTRAP:"

chmod -R 777 ../
chmod -R +rx ../

# unpack bootstrap
mkdir bootstrap
cd bootstrap
cat ../rootfs.tar.xz | ../root/bin/minitar
cd ..

# include resolv.conf
cat bootstrap/etc/resolv.conf
rm bootstrap/etc/resolv.conf

#cat <<EOF > bootstrap/etc/resolv.conf
#nameserver 8.8.8.8
#nameserver 8.8.4.4
#EOF
echo "nameserver 8.8.8.8" > bootstrap/etc/resolv.conf

echo "RESOLV:"
cat bootstrap/etc/resolv.conf

echo "bootstrap ready, run with run-bootstrap.sh"