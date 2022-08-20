



# Install Moonraker
`apk add curl-dev jpeg-dev python3-dev`

`git clone https://github.com/Arksine/moonraker.git`

`cd moonraker`

`pip3 install -U pip setuptools wheel`

```for n in tornado pyserial pillow lmdb libnacl paho-mqtt pycurl streaming-form-data
do
  sed -i "s#$n.*#$n#" ./scripts/moonraker-requirements.txt
done
```

`pip3 install -r scripts/moonraker-requirements.txt`


Run with:
- `LD_PRELOAD=/home/octoprint/ioctlHook.so python3 moonraker/moonraker.py -c ./moonraker.conf -n`


# Install Mainsail

`mkdir -p /etc/nginx/sites-available`

`mkdir /etc/nginx/conf.d`

`mkdir /etc/nginx/sites-enabled`

`rm /etc/nginx/sites-enabled/default`

`ln -s /etc/nginx/sites-available/mainsail /etc/nginx/sites-enabled/`

`echo "manually add `include /etc/nginx/conf.d/*.conf; include /etc/nginx/sites-enabled/*;` to /etc/nginx/nginx.conf http { .. } section"`



Streaming-form-data caused problems with Python 3.11 via pip3.11, manually build and install (Python 3.11 only afaict): 

`git clone https://github.com/siddhantgoel/streaming-form-data`

`pip3.11 install --force-reinstall cython pip3.11 install git+https://github.com/cython/cython.gitpython3.11 setup.py build`

# Install Python 3.11
`apk add alpine-sdk git zlib-dev libffi-dev openssl-dev musl-dev make`

`git clone https://github.com/python/cpython.git`

`cd cpython`

`# ./configure`

`./configure --enable-optimizations`

`make && make install && make inclinstall`

# Moonraker setup
`cd /klipper`

`mkdir logs`

`mkdir config`

`ip` command pretend script:

>> localhost:/klipper# cat scripts/ip 

```
#!/bin/sh
# https://serverfault.com/questions/1031569/how-to-retrieve-the-routing-tables-in-json-format-linux
if [ "$1" == "-json" ] && [ "$2" == "address" ]; then
        /sbin/ip route list table all | jq --raw-input --slurp 'split("\n") | map(capture("^(?:(?<broadcast>broadcast) ?)?(?:(?<local>local) ?)?(?:(?<multicast>multicast) ?)?(?: ?(?<network>.*?) )(?:from (?<from>\\S+) ?)?(?:via (?<via>\\S+) ?)?(?:dev (?<dev>\\S+) ?)?(?:table (?<table>\\S+) ?)?(?:proto (?<proto>\\S+) ?)?(?:scope (?<scope>\\S+) ?)?(?:src (?<src>\\S+) ?)?(?:metric (?<metric>\\d+) ?)?(?<linkdown>linkdown)?(?<unresolved>unresolved)?(?<operstate>operstate)"; "g"))'else
        shift
        /sbin/ip $@
fi
```

# Install Klipper with Python 3.11

`git clone https://github.com/Klipper3d/klipper.git`

`pip3.11 install greenlet==2.0.0a2 pip3.11 install cffi jinja2 pyserial python-can markupsafe`


Missing libs for full-featured py3.11?
```
_bz2                  _curses               _curses_panel      
_dbm                  _gdbm                 _lzma              
_tkinter              _uuid                 nis                
readline      
```

# Scripts to run, stop and pretend to have systemd/systemctl

>> localhost:/klipper# cat start-klipper.sh

```
#!/bin/sh
echo -n "Starting Klipper..."
LD_PRELOAD=/home/octoprint/ioctlHook.so python3.11 /klipper/klipper/klippy/klippy.py /root/printer.cfg -l /klipper/logs/klippy.log -a /tmp/klippy_uds &
MPID=$!
echo "$!" > /var/run/klipper.pid
echo "[background:$MPID]"

echo -n "Starting Moonraker..."
PATH=/klipper/scripts:$PATH LD_PRELOAD=/home/octoprint/ioctlHook.so python3.11 /klipper/moonraker/moonraker/moonraker.py -c /klipper/conf/moonraker.conf -l /klipper/logs/moonraker.log &
KPID=$!
echo "$!" > /var/run/moonraker.pid
echo "[background:$KPID]"
```

>> localhost:/klipper# cat stop-klipper.sh#!/bin/sh
```
kill -9 `cat /var/run/moonraker.pid`
kill -9 `cat /var/run/klipper.pid`
```
